package com.github.lenkan.xaas

import com.rabbitmq.client.*
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.XsltExecutable
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.transform.stream.StreamSource

private val processor: Processor = Processor(false)

fun stylesheets(): Map<String, XsltExecutable> {
    val compiler = processor.newXsltCompiler()
    val root = "/home/lenkan/code/tt/tnt/tools/xslt/"
    return File(root).walkTopDown().filter { file ->
        file.extension == "xsl"
    }.map { file ->
        val key = file.path.replace(root, "").replace(".xsl", "")
        println("Compiling $key")
        val source = StreamSource(file.bufferedReader())
        val executable = compiler.compile(source)
        key to executable
    }.toMap()
}

private fun transform(sheet: XsltExecutable, body: ByteArray): ByteArray {
    val transformer = sheet.load()
    val outputStream = ByteArrayOutputStream()

    transformer.setSource(StreamSource(body.inputStream()))
    transformer.destination = processor.newSerializer(outputStream)
    transformer.transform()
    return outputStream.toByteArray()
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
}

class TransformConsumer(channel: Channel, sheets: Map<String, XsltExecutable>) : DefaultConsumer(channel) {
    private val sheets: Map<String, XsltExecutable> = sheets

    override fun handleDelivery(consumerTag: String?, envelope: Envelope?, properties: AMQP.BasicProperties?, body: ByteArray?) {
        super.handleDelivery(consumerTag, envelope, properties, body)
        if (properties == null || body === null) {
            println("Received request without data or properties, so cannot reply")
            return
        }

        val reply = Reply(channel, properties.replyTo, properties.correlationId)

        if (!properties.headers.containsKey("id")) {
            return reply.badRequest("Received request without id", 400)
        }

        val id = properties.headers.getValue("id").toString()
        if (!sheets.containsKey((id))) {
            return reply.badRequest("No such transform '$id'", 404)
        }

        var sheet = sheets.getValue(id)
        val result = transform(sheet, body)
        return reply.ok(result)
    }
}


fun main(args: Array<String>) {
    val sheets = stylesheets()

    val factory = ConnectionFactory()
    factory.username = "local"
    factory.password = "panda4ever"
    val connection = factory.newConnection()
    val channel = connection.createChannel()
    val consumer = TransformConsumer(channel, sheets)

    channel.queueDeclare(
            "xslt.kt",
            false,
            false,
            true,
            null
    )

    channel.basicConsume("xslt.kt", consumer)
}
