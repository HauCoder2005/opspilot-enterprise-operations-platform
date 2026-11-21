/* global SwaggerUIBundle */
(function () {
  "use strict";

  const unsupportedKeywords = ["oneOf", "anyOf", "allOf", "not", "additionalProperties"];

  function OpsPilotFormEditorPlugin(system) {
    const React = system.React;
    const schemaAwareEditors = new WeakMap();

    function schemaAwareEditor(component, schema) {
      if (!schema || (typeof schema !== "object" && typeof schema !== "function")) return component;
      let editorsBySchema = schemaAwareEditors.get(component);
      if (!editorsBySchema) {
        editorsBySchema = new WeakMap();
        schemaAwareEditors.set(component, editorsBySchema);
      }
      let editor = editorsBySchema.get(schema);
      if (!editor) {
        editor = function SchemaAwareEditor(editorProps) {
          return React.createElement(component, Object.assign({}, editorProps, { schema: schema }));
        };
        editorsBySchema.set(schema, editor);
      }
      return editor;
    }

    function toPlain(value) {
      return value && typeof value.toJS === "function" ? value.toJS() : value;
    }

    function resolveSchema(schema) {
      const plain = toPlain(schema) || {};
      const reference = plain.$ref || plain.$$ref;
      if (!reference || !reference.startsWith("#/")) {
        return plain;
      }
      const document = toPlain(system.specSelectors.specJson());
      const resolved = reference.substring(2).split("/").reduce(function (node, segment) {
        return node && node[segment.replace(/~1/g, "/").replace(/~0/g, "~")];
      }, document);
      return resolved ? resolveSchema(resolved) : plain;
    }

    function isRepresentable(schema, depth) {
      const resolved = resolveSchema(schema);
      if (depth > 8 || unsupportedKeywords.some(function (key) { return resolved[key] !== undefined; })) {
        return false;
      }
      if (resolved.type === "array") {
        return !!resolved.items && isRepresentable(resolved.items, depth + 1);
      }
      if (resolved.type === "object" || resolved.properties) {
        return Object.values(resolved.properties || {}).every(function (property) {
          return isRepresentable(property, depth + 1);
        });
      }
      return [undefined, "string", "integer", "number", "boolean"].includes(resolved.type);
    }

    function initialValue(schema) {
      const resolved = resolveSchema(schema);
      if (resolved.default !== undefined) return resolved.default;
      if (resolved.example !== undefined) return resolved.example;
      if (resolved.type === "array") return [];
      if (resolved.type === "object" || resolved.properties) {
        return Object.entries(resolved.properties || {}).reduce(function (result, entry) {
          const property = resolveSchema(entry[1]);
          if (property.default !== undefined || property.example !== undefined) {
            result[entry[0]] = initialValue(property);
          }
          return result;
        }, {});
      }
      return undefined;
    }

    function parseBody(value, schema) {
      if (value && typeof value === "object") return toPlain(value);
      if (typeof value === "string" && value.trim()) {
        try { return JSON.parse(value); } catch (ignored) { return initialValue(schema) || {}; }
      }
      return initialValue(schema) || {};
    }

    function label(schema, name, required) {
      const resolved = resolveSchema(schema);
      return React.createElement("label", { className: "opspilot-form-label" },
        React.createElement("span", null, name, required ? " *" : ""),
        resolved.description ? React.createElement("small", null, resolved.description) : null);
    }

    function primitiveControl(schema, value, onChange, name) {
      const resolved = resolveSchema(schema);
      const displayedValue = resolved.format === "date-time" && typeof value === "string"
        ? value.replace(/Z$/, "").substring(0, 16) : value;
      const common = {
        id: "opspilot-field-" + name,
        value: displayedValue === undefined || displayedValue === null ? "" : displayedValue,
        required: false,
        placeholder: resolved.example === undefined ? undefined : String(resolved.example),
        onChange: function (event) {
          const raw = event.target.value;
          if (raw === "") return onChange(undefined);
          if (resolved.type === "integer") return onChange(Number.parseInt(raw, 10));
          if (resolved.type === "number") return onChange(Number(raw));
          if (resolved.format === "date-time") return onChange(new Date(raw).toISOString());
          onChange(raw);
        }
      };
      if (Array.isArray(resolved.enum)) {
        return React.createElement("select", common,
          React.createElement("option", { value: "" }, "— Select —"),
          resolved.enum.map(function (option) {
            return React.createElement("option", { key: String(option), value: option }, String(option));
          }));
      }
      if (resolved.type === "boolean") {
        return React.createElement("input", {
          id: common.id,
          type: "checkbox",
          checked: value === true,
          onChange: function (event) { onChange(event.target.checked); }
        });
      }
      let type = "text";
      if (resolved.type === "integer" || resolved.type === "number") type = "number";
      if (resolved.format === "password") type = "password";
      if (resolved.format === "date") type = "date";
      if (resolved.format === "date-time") type = "datetime-local";
      if (resolved.format === "time") type = "time";
      return React.createElement("input", Object.assign({}, common, {
        type: type,
        min: resolved.minimum,
        max: resolved.maximum,
        minLength: resolved.minLength,
        maxLength: resolved.maxLength,
        pattern: resolved.pattern,
        step: resolved.type === "integer" ? 1 : resolved.type === "number" ? "any" : undefined,
        autoComplete: resolved.format === "password" ? "new-password" : undefined
      }));
    }

    function SchemaField(props) {
      const schema = resolveSchema(props.schema);
      const pathName = props.path.join("-");
      if (schema.type === "array") {
        const values = Array.isArray(props.value) ? props.value : [];
        return React.createElement("fieldset", { className: "opspilot-form-group" },
          React.createElement("legend", null, props.name, props.required ? " *" : ""),
          values.map(function (item, index) {
            return React.createElement("div", { className: "opspilot-array-item", key: index },
              React.createElement(SchemaField, {
                schema: schema.items, name: "Item " + (index + 1), value: item,
                required: true, path: props.path.concat(index),
                onChange: function (next) {
                  const copy = values.slice(); copy[index] = next; props.onChange(copy);
                }
              }),
              React.createElement("button", { type: "button", onClick: function () {
                props.onChange(values.filter(function (_, current) { return current !== index; }));
              } }, "Remove"));
          }),
          React.createElement("button", { type: "button", onClick: function () {
            props.onChange(values.concat([initialValue(schema.items)]));
          } }, "Add item"));
      }
      if (schema.type === "object" || schema.properties) {
        const objectValue = props.value && typeof props.value === "object" ? props.value : {};
        const required = new Set(schema.required || []);
        return React.createElement("fieldset", { className: "opspilot-form-group" },
          props.name ? React.createElement("legend", null, props.name, props.required ? " *" : "") : null,
          Object.entries(schema.properties || {}).map(function (entry) {
            return React.createElement(SchemaField, {
              key: entry[0], schema: entry[1], name: entry[0], value: objectValue[entry[0]],
              required: required.has(entry[0]), path: props.path.concat(entry[0]),
              onChange: function (next) {
                const copy = Object.assign({}, objectValue);
                if (next === undefined) delete copy[entry[0]]; else copy[entry[0]] = next;
                props.onChange(copy);
              }
            });
          }));
      }
      return React.createElement("div", { className: "opspilot-form-field" },
        label(schema, props.name, props.required),
        primitiveControl(schema, props.value, props.onChange, pathName));
    }

    return {
      wrapComponents: {
        RequestBody: function (OriginalRequestBody) {
          return function SchemaAwareRequestBody(props) {
            const content = props.requestBody && props.requestBody.get("content");
            const mediaType = props.contentType || (content && content.keySeq().first());
            const schema = content && content.getIn([mediaType, "schema"]);
            const originalGetComponent = props.getComponent;
            const getComponent = function (name) {
              const component = originalGetComponent.apply(null, arguments);
              return name === "RequestBodyEditor" ? schemaAwareEditor(component, schema) : component;
            };
            return React.createElement(OriginalRequestBody, Object.assign({}, props, {
              getComponent: getComponent
            }));
          };
        },
        RequestBodyEditor: function (Original) {
          return function FormRequestBodyEditor(props) {
            const schema = resolveSchema(props.schema);
            if (!schema || (!schema.properties && schema.type !== "object") || !isRepresentable(schema, 0)) {
              return React.createElement(Original, props);
            }
            const state = React.useState(function () {
              return parseBody(props.value || props.defaultValue, schema);
            });
            const value = state[0];
            const setValue = state[1];
            const update = function (next) {
              setValue(next);
              props.onChange(JSON.stringify(next, null, 2));
            };
            return React.createElement("div", { className: "opspilot-request-form" },
              React.createElement(SchemaField, {
                schema: schema, name: null, value: value, required: true, path: [], onChange: update
              }),
              React.createElement("details", { className: "opspilot-json-fallback" },
                React.createElement("summary", null, "Advanced JSON editor"),
                React.createElement(Original, Object.assign({}, props, {
                  value: JSON.stringify(value, null, 2),
                  onChange: function (next) {
                    props.onChange(next);
                    try { setValue(JSON.parse(next)); } catch (ignored) { /* validation remains in Swagger UI */ }
                  }
                }))));
          };
        }
      }
    };
  }

  window.OpsPilotFormEditorPlugin = OpsPilotFormEditorPlugin;
}());
