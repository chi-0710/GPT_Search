const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../frontend/nextjs/next.config.mjs'), 'utf8')
  .replace(/^import withPWAInit.*$/m, '')
  .replace('export default withPWA(nextConfig);', 'globalThis.config = withPWA(nextConfig);');
async function check(env, expected) {
  const sandbox = { process: { env }, withPWAInit: () => config => config };
  vm.runInNewContext(source, sandbox);
  const rules = await sandbox.config.rewrites();
  assert.equal(rules[0].source, '/outputs/:path*');
  assert.equal(rules[0].destination, `${expected}/outputs/:path*`);
}
(async () => {
  await check({}, 'http://localhost:8000');
  await check({ NEXT_PUBLIC_BACKEND_URL: 'https://legacy.test///' }, 'https://legacy.test');
  await check({ NEXT_PUBLIC_GPTR_API_URL: 'https://public.test/', NEXT_PUBLIC_BACKEND_URL: 'https://legacy.test' }, 'https://public.test');
  await check({ INTERNAL_API_URL: 'http://gpt-researcher:8000/', NEXT_PUBLIC_GPTR_API_URL: 'http://localhost:8000', NEXT_PUBLIC_BACKEND_URL: 'https://legacy.test' }, 'http://gpt-researcher:8000');
  console.log('Output proxy precedence and path regression checks passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
