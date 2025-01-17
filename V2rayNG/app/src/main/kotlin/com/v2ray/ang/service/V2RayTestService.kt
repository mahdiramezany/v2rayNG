package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_CANCEL
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_SUCCESS
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MmkvManager
import com.v2ray.ang.util.PluginUtil
import com.v2ray.ang.util.SpeedtestUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.V2rayConfigUtil
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import libv2ray.Libv2ray
import java.util.concurrent.Executors

class V2RayTestService : Service() {
    private val realTestScope by lazy { CoroutineScope(Executors.newFixedThreadPool(10).asCoroutineDispatcher()) }

    override fun onCreate() {
        super.onCreate()
        Seq.setContext(this)
        Libv2ray.initV2Env(Utils.userAssetPath(this), Utils.getDeviceIdForXUDPBaseKey())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getIntExtra("key", 0)) {
            MSG_MEASURE_CONFIG -> {
                val guid = intent.serializable<String>("content") ?: ""
                realTestScope.launch {
                    val result = startRealPing(guid)
                    MessageUtil.sendMsg2UI(this@V2RayTestService, MSG_MEASURE_CONFIG_SUCCESS, Pair(guid, result))
                }
            }

            MSG_MEASURE_CONFIG_CANCEL -> {
                realTestScope.coroutineContext[Job]?.cancelChildren()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val server = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (server.getProxyOutbound()?.protocol?.equals(EConfigType.HYSTERIA2.name, true) == true) {
            val socksPort = Utils.findFreePort(listOf(0))
            PluginUtil.runPlugin(this, server, "0:${socksPort}")
            Thread.sleep(1000L)

            var delay = SpeedtestUtil.testConnection(this, socksPort)
            if (delay.first < 0) {
                Thread.sleep(10L)
                delay = SpeedtestUtil.testConnection(this, socksPort)
            }
            PluginUtil.stopPlugin()
            return delay.first
        } else {
            val config = V2rayConfigUtil.getV2rayConfig(this, guid)
            if (!config.status) {
                return retFailure
            }
            return SpeedtestUtil.realPing(config.content)
        }
    }
}
