package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.network.CameraHttp
import com.example.denmediaipcams.network.PortDetector
import com.example.denmediaipcams.network.PtzProtocolDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Разбор ONVIF на нестандартном порту камеры: что отвечает порт и как его видит приложение. Аргументы -e addHost/addUser/addPass/onvifPort */
@RunWith(AndroidJUnit4::class)
class OnvifPort5000Test {

    @Test
    fun inspectOnvifPort() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("addHost")
        assumeTrue("нет аргументов камеры", host != null)
        host!!
        val port = args.getString("onvifPort")?.toIntOrNull() ?: 5000
        val user = args.getString("addUser").orEmpty()
        val pass = args.getString("addPass").orEmpty()

        println("ONVIF classify $port -> ${PortDetector.classify(host, port)}")

        val envelope = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">""" +
            """<s:Header></s:Header><s:Body><GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/></s:Body></s:Envelope>"""
        val response = CameraHttp.request(
            "http://$host:$port/onvif/device_service", "POST", envelope, "application/soap+xml; charset=utf-8"
        )
        println("ONVIF GetSystemDateAndTime via HttpURLConnection -> code=${response.code} challenged=${response.authChallenged} body=${response.body.take(600)}")

        println("ONVIF findAuthenticatedOnvifPort([5000]) -> ${PortDetector.findAuthenticatedOnvifPort(host, listOf(port), user, pass)}")
        println("ONVIF findAuthenticatedOnvifPort without password -> ${PortDetector.findAuthenticatedOnvifPort(host, listOf(port), "", "")}")
        val streams = com.example.denmediaipcams.network.RtspPathFinder.findAllStreams(host, 554, port, user, pass, null) { }
        println("ONVIF findAllStreams via onvif port $port -> $streams")

        val ptz = PtzProtocolDetector.detect(host, 80, port, user, pass) { }
        println("ONVIF PtzProtocolDetector -> $ptz")
    }
}
