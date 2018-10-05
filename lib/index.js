// @ts-check
const fs = require('fs')
const amqplib = require('amqplib')
const tmp = require('tmp')
const { resolve: resolvePath } = require('path')
const { exec } = require('child_process')

/**
 * Create a text message with the specified content
 * @param {Object} param0
 * @param {string} param0.content
 * @param {number} param0.status
 * @returns {Response}
 */
function textMessage ({ content, status }) {
  return {
    content: Buffer.from(content, 'utf8'),
    properties: {
      contentEncoding: 'utf8',
      contentType: 'text/plain',
      headers: {
        status
      }
    }
  }
}

async function connect (url) {
  const connection = await amqplib.connect(url)
  async function shutdown (code, reason) {
    const msg = reason
      ? `Shutting down: ${reason}`
      : 'Shutting down...'
    console.log(msg)

    try {
      await connection.close()
      process.exit(code)
    } catch (error) {
      process.exit(1)
    }
  }

  connection.on('error', err => shutdown(1, err.message || err))
  process.on('SIGTERM', () => shutdown(0))
  return connection
}

function createTempFile (content) {
  return new Promise((resolve, reject) => {
    tmp.file(async (err, path) => {
      if (err) {
        return reject(err)
      }

      fs.writeFile(path, content, err => {
        if (err) {
          return reject(err)
        }

        return resolve(path)
      })
    })
  })
}

function runSaxon ({ input, filter }) {
  return new Promise((resolve, reject) => {
    const command = `java -jar ./saxon.jar ${input} ${filter}`
    exec(command, (err, stdout) => {
      if (err) {
        return reject(err)
      }

      return resolve(stdout)
    })
  })
}

async function readFilters (dir) {
  const abs = resolvePath(dir)
  return new Promise((resolve, reject) => {
    fs.readdir(dir, (err, names) => {
      if (err) {
        return reject(err)
      }

      const filters = names.filter(isStylesheet).reduce((result, name) => {
        return Object.assign(result, {
          [name.replace(/\.xsl$/, '')]: resolvePath(abs, name)
        })
      }, {})

      console.log(`Found ${Object.keys(filters).length} filters in ${dir}:`)
      console.log(Object.keys(filters).map(key => `${key}: ${filters[key]}`).join('\n'))
      return resolve(filters)
    })
  })
}

function isStylesheet (name) {
  return name.endsWith('.xsl')
}

/**
 * @typedef {{ content: Buffer, properties: Partial<import('amqplib').MessageProperties> }} Response
 * @typedef {(msg: import('amqplib').Message) => Promise<Response> |  Response} Handler
 *
 * @param {Object} param0
 * @param {import('amqplib').Connection} [param0.connection]
 * @param {string} [param0.url]
 * @param {string} param0.queue
 * @param {string} param0.filterDir
 */
async function init ({ connection, url, queue, filterDir }) {
  if (!filterDir) {
    throw new Error('No filter directory specified')
  }
  if (!queue) {
    throw new Error('No listen queue specified')
  }

  const filters = await readFilters(filterDir)
  const conn = connection || await connect(url)
  const channel = await conn.createChannel()

  /**
   * @param {import('amqplib').Message} msg
   * @returns {Promise<Response>}
   */
  async function handler (msg) {
    try {
      const { content, properties: { headers } } = msg

      if (!headers.id) {
        return textMessage({
          content: 'No filter id specified in AMQP message headers',
          status: 400
        })
      }

      const filter = filters[headers.id]
      if (!filter) {
        return textMessage({
          content: `Could not find the specified filter ${headers.id}`,
          status: 404
        })
      }

      const result = await runSaxon({
        input: await createTempFile(content),
        filter
      })

      return textMessage({ content: result, status: 200 })
    } catch (error) {
      return textMessage({ content: error.message, status: 500 })
    }
  }

  await channel.assertQueue(queue, {
    autoDelete: true,
    durable: true
  })

  await channel.consume(queue, async msg => {
    const { properties: { replyTo, correlationId } } = msg
    console.log(`REQUEST '${msg.properties.headers.id}'`)

    const result = await handler(msg)
    console.log(`REPLY '${msg.properties.headers.id}' ${result.properties.headers.status}`)

    const properties = Object.assign({ correlationId }, result.properties)
    channel.sendToQueue(replyTo, result.content, properties)
  }, { noAck: false })

  console.log(`Listening on ${queue}`)
}

module.exports = init
