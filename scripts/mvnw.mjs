#!/usr/bin/env node
// Cross-platform entry point to the Maven wrapper.
//
// `./mvnw` in an npm script breaks on Windows: those scripts run through
// cmd.exe, which has no `./` and cannot execute a POSIX shell script. Windows
// needs `mvnw.cmd`. This picks the right one so `pnpm backend:test` behaves the
// same on macOS, Linux and Windows.
//
// Usage:  node scripts/mvnw.mjs verify
//         node scripts/mvnw.mjs spring-boot:run -Dspring-boot.run.profiles=local

import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const backend = join(repoRoot, 'backend')
const isWindows = process.platform === 'win32'
const wrapper = join(backend, isWindows ? 'mvnw.cmd' : 'mvnw')

const child = spawn(wrapper, process.argv.slice(2), {
  cwd: backend,
  stdio: 'inherit',
  // cmd.exe needs a shell to run a .cmd file; POSIX does not, and avoiding the
  // shell there keeps arguments from being re-parsed.
  shell: isWindows,
})

child.on('error', (error) => {
  console.error(`Could not run ${wrapper}: ${error.message}`)
  if (!isWindows && error.code === 'EACCES') {
    console.error('Try: chmod +x backend/mvnw')
  }
  process.exit(1)
})

child.on('exit', (code, signal) => {
  process.exit(signal ? 1 : (code ?? 1))
})
