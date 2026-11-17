/**
 * Tests for "Edit column > Translate column…".
 *
 * These tests never call a real translation service. They need OpenRefine to be started
 * pointing at the fake LibreTranslate server that the Cypress plugin runs (see
 * cypress/plugins/fake-libretranslate.js):
 *
 *   LIBRETRANSLATE_URL=http://localhost:5055 ./refine e2e_tests
 */
describe(__filename, function () {
  const MENU = ['Edit column', 'Translate column…'];
  const DIALOG = 'body > .dialog-container > .dialog-frame';

  const reviews = () => [
    ['id', 'review', 'other'],
    ['1', 'hello', 'x'],
    ['2', '', 'y'],
    ['3', 'thank you', 'z'],
  ];

  const openDialog = (columnName = 'review') => {
    cy.columnActionClick(columnName, MENU);
    cy.waitForDialogPanel();
  };

  const okButton = () => cy.get(`${DIALOG} button[bind="okButton"]`);

  /**
   * Waits until the fake LibreTranslate has received a request. Fails with a clear message if it
   * never does, e.g. because OpenRefine was started without LIBRETRANSLATE_URL pointing at it.
   */
  const assertFakeServerWasCalled = () => {
    cy.task('fakeLibreTranslate:waitForRequests', 10000, { timeout: 15000 }).then((count) => {
      expect(
        count,
        'requests received by the fake LibreTranslate. If 0, start OpenRefine with ' +
          `LIBRETRANSLATE_URL=http://localhost:${Cypress.expose('FAKE_LIBRETRANSLATE_PORT')}`
      ).to.be.greaterThan(0);
    });
  };

  beforeEach(function () {
    cy.task('fakeLibreTranslate:up');
    cy.task('fakeLibreTranslate:resetRequestCount');
  });

  afterEach(function () {
    // Leave the fake server running for the next test, even if a test failed while it was down.
    cy.task('fakeLibreTranslate:up');
  });

  it('opens the dialog from the column menu', function () {
    cy.loadAndVisitProject(reviews());
    openDialog();

    cy.get(`${DIALOG} .dialog-header`).should('have.text', 'Translate column review');
    cy.get(`${DIALOG} label[bind="or_views_sourceLang"]`).should('have.text', 'Source language');
    cy.get(`${DIALOG} label[bind="or_views_targetLang"]`).should('have.text', 'Target language');
    cy.get(`${DIALOG} label[bind="or_views_newCol"]`).should('have.text', 'New column name');
    cy.get(`${DIALOG} [bind="or_views_onErr"]`).should('have.text', 'On error');

    cy.get(`${DIALOG} select[bind="sourceLangSelect"] option`)
      .then((options) => Cypress.$.map(options, (o) => o.innerText))
      .should('deep.equal', ['Detect automatically', 'English', 'Spanish', 'Mandarin Chinese']);
    cy.get(`${DIALOG} select[bind="targetLangSelect"] option`)
      .then((options) => Cypress.$.map(options, (o) => o.innerText))
      .should('deep.equal', ['Choose a language…', 'English', 'Spanish', 'Mandarin Chinese']);

    // No target chosen yet: OK must be disabled (D2).
    okButton().should('be.disabled');
    cy.get(`${DIALOG} input[name="translate-column-onerror-choice"][value="set-to-blank"]`).should('be.checked');
  });

  it('chooses the source and target languages and sends them to the backend', function () {
    cy.loadAndVisitProject(reviews());
    cy.intercept('POST', '**/command/core/translate-column*').as('translate');
    openDialog();

    // The default name follows the target language (D3).
    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).should('have.value', 'review_es');
    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Mandarin Chinese');
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).should('have.value', 'review_zh');
    okButton().should('be.enabled');

    // Same source and target: inline warning, OK disabled (D2).
    cy.get(`${DIALOG} select[bind="sourceLangSelect"]`).select('Mandarin Chinese');
    cy.get(`${DIALOG} [bind="languageWarning"]`)
      .should('be.visible')
      .and('have.text', 'The source and target languages must be different.');
    okButton().should('be.disabled');

    cy.get(`${DIALOG} select[bind="sourceLangSelect"]`).select('English');
    cy.get(`${DIALOG} [bind="languageWarning"]`).should('not.be.visible');

    // Once the user edits the name, changing the target no longer overwrites it (D3).
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).clear();
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).type('review_translated');
    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).should('have.value', 'review_translated');

    cy.get(`${DIALOG} input[name="translate-column-onerror-choice"][value="store-error"]`).check();
    cy.confirmDialogPanel();

    cy.wait('@translate').then((interception) => {
      const params = new URL(interception.request.url).searchParams;
      expect(params.get('baseColumnName')).to.equal('review');
      expect(params.get('sourceLang')).to.equal('en');
      expect(params.get('targetLang')).to.equal('es');
      expect(params.get('newColumnName')).to.equal('review_translated');
      expect(params.get('onError')).to.equal('store-error');
    });
  });

  it('adds the translated column next to the original with rows aligned', function () {
    cy.loadAndVisitProject(reviews());
    openDialog();

    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    cy.confirmDialogPanel();
    assertFakeServerWasCalled();

    cy.get('table.data-table thead th[title="review_es"]').should('exist');

    // New column directly right of "review"; each translation on its own row; empty cell stays empty.
    cy.assertGridEquals([
      ['id', 'review', 'review_es', 'other'],
      ['1', 'hello', 'hola', 'x'],
      ['2', null, null, 'y'],
      ['3', 'thank you', 'gracias', 'z'],
    ]);
  });

  it('warns when the new column name already exists and never overwrites it', function () {
    cy.loadAndVisitProject([
      ['review', 'review_es'],
      ['hello', 'old value'],
    ]);
    openDialog();

    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).should('have.value', 'review_es');
    cy.get(`${DIALOG} [bind="nameWarning"]`)
      .should('be.visible')
      .and('have.text', 'A column named "review_es" already exists. Please choose a different name.');
    okButton().should('be.disabled');

    // A unique name clears the warning and re-enables OK (D4).
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).clear();
    cy.get(`${DIALOG} input[bind="columnNameInput"]`).type('review_es_new');
    cy.get(`${DIALOG} [bind="nameWarning"]`).should('not.be.visible');
    okButton().should('be.enabled');
    cy.confirmDialogPanel();
    assertFakeServerWasCalled();

    cy.get('table.data-table thead th[title="review_es_new"]').should('exist');
    cy.assertGridEquals([
      ['review', 'review_es_new', 'review_es'],
      ['hello', 'hola', 'old value'],
    ]);
  });

  it('shows an error and adds no column when the translation service is unreachable', function () {
    cy.loadAndVisitProject(reviews());
    // Cypress accepts alerts automatically; capture the text to check it.
    const alertStub = cy.stub();
    cy.on('window:alert', alertStub);
    cy.task('fakeLibreTranslate:down');

    openDialog();
    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    cy.confirmDialogPanel();

    cy.wrap(alertStub).should('have.been.calledOnce');
    cy.wrap(alertStub).then((stub) => {
      const message = stub.getCall(0).args[0];
      expect(message).to.contain('The last operation encountered some errors:');
      expect(message).to.contain(
        `Translation service unreachable at http://localhost:${Cypress.expose('FAKE_LIBRETRANSLATE_PORT')}`
      );
      expect(message).to.contain('Is LibreTranslate running?');
    });

    cy.get('table.data-table thead th[title="review_es"]').should('not.exist');
    cy.assertGridEquals(reviews().map((row) => row.map((v) => (v === '' ? null : v))));
  });

  it('shows the error inside the dialog when the request is rejected', function () {
    cy.loadAndVisitProject(reviews());
    cy.intercept('POST', '**/command/core/translate-column*', {
      statusCode: 200,
      body: { code: 'error', message: 'Unknown target language: xx' },
    }).as('translate');

    openDialog();
    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    okButton().click();
    cy.wait('@translate');

    // The dialog stays open with the message, and OK can be clicked again (D6).
    cy.get(DIALOG).should('be.visible');
    cy.get(`${DIALOG} [bind="requestError"]`)
      .should('be.visible')
      .and('have.text', 'The translation could not be started: Unknown target language: xx');
    okButton().should('be.enabled');

    cy.cancelDialogPanel();
    cy.get('table.data-table thead th[title="review_es"]').should('not.exist');
  });

  it('cancel closes the dialog without changing anything', function () {
    cy.loadAndVisitProject(reviews());
    cy.intercept('POST', '**/command/core/translate-column*').as('translate');

    openDialog();
    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    cy.cancelDialogPanel();

    cy.get('table.data-table thead th[title="review_es"]').should('not.exist');
    cy.get('@translate.all').should('have.length', 0);
    cy.task('fakeLibreTranslate:requestCount').should('equal', 0);
    cy.get('a#or-proj-undoRedo > span.count').invoke('text').should('equal', '0 / 0');
  });

  it('undo removes the translated column', function () {
    cy.loadAndVisitProject(reviews());
    openDialog();
    cy.get(`${DIALOG} select[bind="targetLangSelect"]`).select('Spanish');
    cy.confirmDialogPanel();
    assertFakeServerWasCalled();

    cy.get('table.data-table thead th[title="review_es"]').should('exist');
    cy.get('a#or-proj-undoRedo > span.count').invoke('text').should('equal', '1 / 1');

    cy.get('.notification-action a').should('to.contain', 'Undo').click();

    cy.get('table.data-table thead th[title="review_es"]').should('not.exist');
    cy.get('a#or-proj-undoRedo > span.count').invoke('text').should('equal', '0 / 1');
    cy.assertGridEquals(reviews().map((row) => row.map((v) => (v === '' ? null : v))));
  });
});
