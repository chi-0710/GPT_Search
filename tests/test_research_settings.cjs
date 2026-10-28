// Isolated frontend validation; no browser or application server.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const ts = require(path.join(root, 'frontend/nextjs/node_modules/typescript'));
const source = fs.readFileSync(path.join(root, 'frontend/nextjs/utils/researchSettings.ts'), 'utf8');
const sandbox = {exports: {}};
vm.runInNewContext(ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText, sandbox);
const validate = sandbox.exports.validateResearchSettings;
assert.ok(validate({report_source: 'hybrid', document_scope: 'selected', document_ids: []}));
assert.equal(validate({report_source: 'hybrid', document_scope: 'selected', document_ids: ['A']}), null);
assert.equal(validate({report_source: 'local', document_scope: 'all'}), null);
assert.ok(validate({report_source: 'local', report_type: 'multi_agents'}));
assert.equal(validate({report_source: 'web', report_type: 'multi_agents', document_scope: 'selected'}), null);
assert.equal(validate({report_source: 'hybrid'}), null);
console.log('6 research settings assertions passed');
