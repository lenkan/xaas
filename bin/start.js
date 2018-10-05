#! /usr/bin/env node
const start = require('../lib/index')

const username = process.env.AMQP_USER || 'guest'
const password = process.env.AMQP_PASSWORD || 'guest'
const hostname = process.env.AMQP_HOST || 'localhost'
const vhost = process.env.AMQP_VHOST || '/'
const url = process.env.AMQP_URL || `amqp://${username}:${password}@${hostname}${vhost}`
const queue = process.env.AMQP_QUEUE || 'xslt.rpc'
const filterDir = process.env.FILTER_PATH

start({
  url,
  queue,
  filterDir
}).catch(error => {
  console.error(error)
  process.exit(1)
})
