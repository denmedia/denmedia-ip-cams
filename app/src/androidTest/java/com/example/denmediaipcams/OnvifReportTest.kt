package com.example.denmediaipcams

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.denmediaipcams.network.OnvifInspector
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Полный ONVIF-отчёт приложения по камере (только чтение). Аргументы: -e addHost, -e onvifPort, -e addUser, -e addPass */
@RunWith(AndroidJUnit4::class)
class OnvifReportTest {

    @Test
    fun printReport() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("addHost")
        assumeTrue("нет аргументов камеры", host != null)
        val report = OnvifInspector.inspect(
            host!!, args.getString("onvifPort")?.toIntOrNull() ?: 80,
            args.getString("addUser").orEmpty(), args.getString("addPass").orEmpty()
        )
        report.lines().forEach { println("REPORT[$host] $it") }
    }
}
