// / <reference types="cypress" />
// ***********************************************************
// This example plugins/index.js can be used to load plugins
//
// You can change the location of this file or turn off loading
// the plugins file with the 'pluginsFile' configuration option.
//
// You can read more here:
// https://on.cypress.io/plugins-guide
// ***********************************************************

// This function is called when a project is opened or re-opened (e.g. due to
// the project's config changing)
/**
 * @type {Cypress.PluginConfig}
 */

const { createFakeLibreTranslate, DEFAULT_PORT } = require('./fake-libretranslate');

module.exports = async (on, config) => {
  // `on` is used to hook into various events Cypress emits
  // `config` is the resolved Cypress config

  // Fake translation server for the "Translate column" tests (see fake-libretranslate.js).
  const port = Number(config.env.FAKE_LIBRETRANSLATE_PORT) || DEFAULT_PORT;
  const fakeLibreTranslate = createFakeLibreTranslate(port);
  try {
    await fakeLibreTranslate.start();
  } catch (e) {
    // Do not break unrelated specs; the translate-column spec reports this clearly.
    console.warn(`Fake LibreTranslate could not start on port ${port}: ${e.message}`);
  }
  on('task', fakeLibreTranslate.tasks);
  config.expose = { ...config.expose, FAKE_LIBRETRANSLATE_PORT: port };

  return config;
};
