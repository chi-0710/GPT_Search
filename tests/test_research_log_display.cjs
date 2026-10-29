const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '..');
const ts = require(path.join(root, 'frontend/nextjs/node_modules/typescript'));
const source = fs.readFileSync(path.join(root, 'frontend/nextjs/utils/researchLogDisplay.ts'), 'utf8');
const sandbox = { exports: {} };
vm.runInNewContext(ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, sandbox);
const { getResearchFieldLabel, formatResearchLogValue, getResearchLogFields } = sandbox.exports;

assert.equal(getResearchFieldLabel('research_data'), '研究资料');
assert.equal(getResearchFieldLabel('custom_field'), 'custom_field');
assert.equal(getResearchFieldLabel('__proto__'), '__proto__');

const layoutText = '{"introduction":"\\u4e2d\\u6587","sources":["https://example.com"]}';
const layout = getResearchLogFields('research_layout_content', layoutText);
assert.equal(layout.introduction, '中文');
assert.equal(formatResearchLogValue(layout), '引言：中文\n来源：https://example.com');
assert.equal(Object.keys(layout).join(','), 'introduction,sources');

const differences = getResearchLogFields('differences', JSON.stringify({ data: {
  plan_revision_count: { before: 1, after: 0 },
  draft: { before: 'old', after: '' },
  custom_field: { before: true, after: false },
  title: { before: '原标题' },
  review: { before: '旧意见', after: null },
} }));
assert.equal(differences.plan_revision_count, 0);
assert.equal(differences.draft, '');
assert.equal(differences.custom_field, false);
assert.equal(differences.title, '原标题');
assert.equal(differences.review, null);

for (const text of ['invalid JSON', 'null', '[]', '{"data":null}', '{"data":{"title":1}}']) {
  assert.equal(getResearchLogFields('differences', text), null);
}
assert.equal(getResearchLogFields('research_layout_content', '[]'), null);
assert.equal(getResearchLogFields('unrelated_event', layoutText), null);
const original = { task: { title: '主题' }, sections: ['甲', '乙'] };
const snapshot = JSON.stringify(original);
assert.equal(formatResearchLogValue(original), '任务：标题：主题\n章节：甲\n乙');
assert.equal(JSON.stringify(original), snapshot);
console.log('Research log display tests passed.');
