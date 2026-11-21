"use strict";

const assert = require("node:assert/strict");
const path = require("node:path");

global.window = {};
require(path.resolve(__dirname,
  "../../main/resources/META-INF/resources/webjars/swagger-ui/5.34.0/opspilot-form-editor.js"));

const React = {
  createElement: function (type, props) { return { type: type, props: props }; }
};
const system = {
  React: React,
  specSelectors: { specJson: function () { return {}; } }
};
const plugin = window.OpsPilotFormEditorPlugin(system);
const OriginalRequestBody = function OriginalRequestBody() {};
const OriginalEditor = function OriginalEditor() {};
const schema = {};
const content = {
  keySeq: function () { return { first: function () { return "application/json"; } }; },
  getIn: function () { return schema; }
};
const requestBody = {
  get: function (name) { return name === "content" ? content : undefined; }
};
const props = {
  requestBody: requestBody,
  contentType: "application/json",
  getComponent: function () { return OriginalEditor; }
};
const SchemaAwareRequestBody = plugin.wrapComponents.RequestBody(OriginalRequestBody);

const firstRender = SchemaAwareRequestBody(props);
const secondRender = SchemaAwareRequestBody(props);
const firstEditorType = firstRender.props.getComponent("RequestBodyEditor");
const secondEditorType = secondRender.props.getComponent("RequestBodyEditor");

assert.strictEqual(firstEditorType, secondEditorType,
  "RequestBodyEditor component identity must remain stable across parent re-renders");
assert.notStrictEqual(firstEditorType, OriginalEditor,
  "The stable adapter must still inject the request schema");
assert.strictEqual(firstEditorType({ marker: true }).props.schema, schema,
  "The adapter must inject the same schema without recreating its component type");

console.log("Swagger form editor identity regression test: PASS");
