package com.github.lenkan.xaas

import com.rabbitmq.client.*
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.XsltExecutable
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.transform.stream.StreamSource

fun stylesheets(): Map<String, XsltExecutable> {
    val compiler = Processor(false).newXsltCompiler()
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

class TransformConsumer(channel: Channel, sheets: Map<String, XsltExecutable>) : DefaultConsumer(channel) {
    private val sheets: Map<String, XsltExecutable> = sheets
    private val processor: Processor = Processor(false)

    override fun handleDelivery(consumerTag: String?, envelope: Envelope?, properties: AMQP.BasicProperties?, body: ByteArray?) {
        super.handleDelivery(consumerTag, envelope, properties, body)
        println("Received something")
        if (properties == null || body === null) {
            println("Received request without data or properties")
            return
        }

        val headers = properties.headers
        if (!headers.containsKey("id")) {
            println("Received request without id")
            return
        }

        val id = headers.getValue("id").toString()

        println("Received request for $id")
        val executable = this.sheets.getValue(id)
        println("Has executable $id")
        val transformer = executable.load()
        val outputStream = ByteArrayOutputStream()

        println("Before Transforming $id")
        transformer.setSource(StreamSource(body.inputStream()))
        transformer.destination = processor.newSerializer(outputStream)

        println("Transforming $id")
        kotlin.run { transformer.transform() }
        outputStream.toByteArray()

        val outProperties = AMQP.BasicProperties.Builder()
                .correlationId(properties.correlationId)
                .contentEncoding("utf8")
                .contentType("text/plain")
                .build()

        channel.basicPublish("", properties.replyTo, outProperties, outputStream.toByteArray())
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
