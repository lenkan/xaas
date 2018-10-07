package com.github.lenkan.xaas

import com.rabbitmq.client.*
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.XsltExecutable
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Exception
import javax.xml.transform.stream.StreamSource

private val processor: Processor = Processor(false)
private val hostname: String = System.getenv("AMQP_HOST") ?: "localhost"
private val username: String = System.getenv("AMQP_USER") ?: "guest"
private val password: String = System.getenv("AMQP_PASSWORD") ?: "guest"
private val virtualHost: String = System.getenv("AMQP_VHOST") ?: "/"
private val port: Int = (System.getenv("AMQP_PORT") ?: "5672").toInt()
private val queueName: String = System.getenv("AMQP_QUEUE") ?: "xaas.transform"
private val durable: Boolean = System.getenv("AMQP_DURABLE").parseBoolean(false)
private val exclusive: Boolean = System.getenv("AMQP_EXCLUSIVE").parseBoolean(false)
private val autoDelete: Boolean = System.getenv("AMQP_AUTODELETE").parseBoolean(true)
private val root: String = System.getenv("XSLT_ROOT") ?: System.getProperty("user.home")+"/.xaas"

fun String?.parseBoolean(default: Boolean): Boolean = when (this) {
    null -> default
    "0" -> false
    "false" -> false
    else -> true
}

fun sheetId(path: String): String {
    return path.replace(root, "")
            .replace(".xsl", "")
            .replace(Regex("^/"), "")
}

interface Transform {
    fun exec(body: ByteArray): ByteArray
}

class CompiledTransform(private val executable: XsltExecutable) : Transform {
    override fun exec(body: ByteArray): ByteArray {
        val transformer = executable.load()
        val outputStream = ByteArrayOutputStream()

        transformer.setSource(StreamSource(body.inputStream()))
        transformer.destination = processor.newSerializer(outputStream)
        transformer.transform()
        return outputStream.toByteArray()
    }
}

class Transforms {
    private var transforms: Map<String, Transform> = emptyMap()
    private val compiler = processor.newXsltCompiler()

    fun load() {
        transforms = File(root).walkTopDown().filter { file ->
            file.extension == "xsl"
        }.map { file ->
            val key = sheetId(file.path)
            val source = StreamSource(file.bufferedReader())
            val executable = compiler.compile(source)
            println("Compiled $key")
            key to CompiledTransform(executable)
        }.toMap()
        val total = transforms.size
        println("Compiled $total stylesheets")
        println("")
    }

    fun get(id: String): Transform? {
        return transforms.getOrDefault(id, null)
    }
}


class Reply(private val channel: Channel, private val replyTo: String, private val correlationId: String) {
    fun badRequest(msg: String, status: Int = 400) {
        val outProperties = AMQP.BasicProperties.Builder()
                .contentType("text/plain")
                .contentEncoding("utf8")
                .correlationId(correlationId)
                .headers(mapOf("status" to status))
                .build()

        channel.basicPublish(
                "",
                replyTo,
                outProperties,
                msg.toByteArray()
        )
    }

    fun ok(body: ByteArray) {
        val outProperties = AMQP.BasicProperties.Builder()
                .contentType("text/plain")
                .contentEncoding("utf8")
                .correlationId(correlationId)
                .headers(mapOf("status" to 200))
                .build()

        channel.basicPublish(
                "",
                replyTo,
                outProperties,
                body
        )
    }


    fun error(e: Exception) {
        val outProperties = AMQP.BasicProperties.Builder()
                .contentType("text/plain")
                .contentEncoding("utf8")
                .correlationId(correlationId)
                .headers(mapOf("status" to 500))
                .build()

        val body = e.message ?: "Unexpected error on server"
        channel.basicPublish(
                "",
                replyTo,
                outProperties,
                body.toByteArray()
        )
    }
}

class TransformConsumer(channel: Channel, private val transforms: Transforms) : DefaultConsumer(channel) {
    override fun handleDelivery(consumerTag: String?, envelope: Envelope?, properties: AMQP.BasicProperties?, body: ByteArray?) {
        super.handleDelivery(consumerTag, envelope, properties, body)
        if (properties == null || body === null) {
            println("Received request without data or properties, so cannot reply")
            return
        }
        val reply = Reply(channel, properties.replyTo, properties.correlationId)
        try {
            if (!properties.headers.containsKey("id")) {
                return reply.badRequest("Received request without id", 400)
            }

            val id = properties.headers.getValue("id").toString()
            val transform = transforms.get(id) ?: return reply.badRequest("No such transform '$id'", 404)
            val result = transform.exec(body)
            return reply.ok(result)
        } catch (e: Exception) {
            return reply.error(e)
        }
    }
}


fun main(args: Array<String>) {
    val transforms = Transforms()
    transforms.load()

    val factory = ConnectionFactory()
    factory.username = username
    factory.host = hostname
    factory.password = password
    factory.port = port
    factory.virtualHost = virtualHost
    val connection = factory.newConnection()
    val channel = connection.createChannel()
    println("Connected to $hostname:$port$virtualHost as user '$username'")
    println()


    val consumer = TransformConsumer(channel, transforms)
    channel.queueDeclare(queueName, durable, exclusive, autoDelete, null)
    channel.basicConsume(queueName, consumer)

    println("Listening on $queueName with settings:")
    println("- durable: $durable")
    println("- exclusive: $exclusive")
    println("- auto-delete: $autoDelete")
}
