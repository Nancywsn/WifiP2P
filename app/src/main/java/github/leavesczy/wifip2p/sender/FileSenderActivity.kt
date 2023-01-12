package github.leavesczy.wifip2p.sender

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import github.leavesczy.wifip2p.*
import github.leavesczy.wifip2p.models.ViewState
import github.leavesczy.wifip2p.utils.WifiP2pUtils
import kotlinx.android.synthetic.main.activity_file_sender.*
import kotlinx.coroutines.launch

/**
 * @Author: leavesCZY
 * @Desc:
 */
@SuppressLint("NotifyDataSetChanged")
class FileSenderActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_sender)
        initView()
        initDevice()
        initEvent()
    }

    @SuppressLint("MissingPermission")
    private fun initView() {
        supportActionBar?.title = "文件发送端"   //标题栏文字
        btnDisconnect.setOnClickListener {
            disconnect()
        }
        btnChooseFile.setOnClickListener {
            getContentLaunch.launch("image/*")
        }
        btnDirectDiscover.setOnClickListener {
            if (!wifiP2pEnabled) {
                showToast("需要先打开Wifi")
                return@setOnClickListener
            }
            showLoadingDialog(message = "正在搜索附近设备") //弹出对话框
            wifiP2pDeviceList.clear()
            deviceAdapter.notifyDataSetChanged()
            wifiP2pManager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    showToast("discoverPeers Success")
                    dismissLoadingDialog()
                }

                override fun onFailure(reasonCode: Int) {
                    showToast("discoverPeers Failure：$reasonCode")
                    dismissLoadingDialog()
                }
            })
        }
        deviceAdapter.onItemClickListener = object : OnItemClickListener {
            override fun onItemClick(position: Int) {
                val wifiP2pDevice = wifiP2pDeviceList.getOrNull(position)
                if (wifiP2pDevice != null) {
                    connect(wifiP2pDevice = wifiP2pDevice)
                }
            }
        }

        //自定义控件的适配器
        rvDeviceList.adapter = deviceAdapter
        rvDeviceList.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean {
                return false
            }
        }
    }

    private fun initDevice() {
        //注册P2P广播，以便获取周边设备信息以及连接状态
        val mWifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mWifiP2pManager == null) {
            finish()
            return
        }
        wifiP2pManager = mWifiP2pManager
        wifiP2pChannel = mWifiP2pManager.initialize(this, mainLooper, directActionListener)
        broadcastReceiver =
            DirectBroadcastReceiver(mWifiP2pManager, wifiP2pChannel, directActionListener)
        registerReceiver(broadcastReceiver, DirectBroadcastReceiver.getIntentFilter())
    }

    private fun initEvent() {
        lifecycleScope.launch {
            fileSenderViewModel.viewState.collect {
                when (it) {
                    ViewState.Idle -> {
                        clearLog()
                        dismissLoadingDialog()
                    }

                    ViewState.Connecting -> {
                        showLoadingDialog(message = "")
                    }

                    is ViewState.Receiving -> {
                        showLoadingDialog(message = "")
                    }

                    is ViewState.Success -> {
                        dismissLoadingDialog()
                    }

                    is ViewState.Failed -> {
                        dismissLoadingDialog()
                    }
                }
            }
        }
        lifecycleScope.launch {
            fileSenderViewModel.log.collect {
                log(it)
            }
        }
    }


    private val fileSenderViewModel by viewModels<FileSenderViewModel>()

    private val getContentLaunch = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imageUri ->
        if (imageUri != null) {
            val ipAddress = wifiP2pInfo?.groupOwnerAddress?.hostAddress
            log("getContentLaunch $imageUri $ipAddress")
            if (!ipAddress.isNullOrBlank()) {
                fileSenderViewModel.send(ipAddress = ipAddress, fileUri = imageUri)
            }
        }
    }

    private val wifiP2pDeviceList = mutableListOf<WifiP2pDevice>()  //MutableList接口的初始化，一个接口和通用的元素集合

    private val deviceAdapter = DeviceAdapter(wifiP2pDeviceList)    //

    private var broadcastReceiver: BroadcastReceiver? = null

    private lateinit var wifiP2pManager: WifiP2pManager     //对等网络管理器

    private lateinit var wifiP2pChannel: WifiP2pManager.Channel

    private var wifiP2pInfo: WifiP2pInfo? = null

    private var wifiP2pEnabled = false

    private val directActionListener = object : DirectActionListener {

        override fun wifiP2pEnabled(enabled: Boolean) {
            wifiP2pEnabled = enabled
        }

        //信息最后通过 onConnectionInfoAvailable 方法传递出来，在此可以判断当前设备是否为群主，获取群组IP地址
        override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
            dismissLoadingDialog()
            wifiP2pDeviceList.clear()
            deviceAdapter.notifyDataSetChanged()
            btnDisconnect.isEnabled = true
            btnChooseFile.isEnabled = true
            log("onConnectionInfoAvailable")
            log("onConnectionInfoAvailable groupFormed: " + wifiP2pInfo.groupFormed)
            log("onConnectionInfoAvailable isGroupOwner: " + wifiP2pInfo.isGroupOwner)
            log("onConnectionInfoAvailable getHostAddress: " + wifiP2pInfo.groupOwnerAddress.hostAddress)
            val stringBuilder = StringBuilder()
            stringBuilder.append("\n")
            stringBuilder.append("是否群主：")
            stringBuilder.append(if (wifiP2pInfo.isGroupOwner) "是群主" else "非群主")
            stringBuilder.append("\n")
            stringBuilder.append("群主IP地址：")
            stringBuilder.append(wifiP2pInfo.groupOwnerAddress.hostAddress)
            tvConnectionStatus.text = stringBuilder
            if (wifiP2pInfo.groupFormed && !wifiP2pInfo.isGroupOwner) {
                this@FileSenderActivity.wifiP2pInfo = wifiP2pInfo
            }
        }

        override fun onDisconnection() {
            log("onDisconnection")
            btnDisconnect.isEnabled = false
            btnChooseFile.isEnabled = false
            wifiP2pDeviceList.clear()
            deviceAdapter.notifyDataSetChanged()
            tvConnectionStatus.text = null
            wifiP2pInfo = null
            showToast("处于非连接状态")
        }

        //显示本设备信息
        override fun onSelfDeviceAvailable(wifiP2pDevice: WifiP2pDevice) {
            log("onSelfDeviceAvailable")
            log("DeviceName: " + wifiP2pDevice.deviceName)
            log("DeviceAddress: " + wifiP2pDevice.deviceAddress)
            log("Status: " + wifiP2pDevice.status)
            val log = "deviceName：" + wifiP2pDevice.deviceName + "\n" +
                    "deviceAddress：" + wifiP2pDevice.deviceAddress + "\n" +
                    "deviceStatus：" + WifiP2pUtils.getDeviceStatus(wifiP2pDevice.status)
            tvDeviceState.text = log
        }

        //刷新可用设备列表
        override fun onPeersAvailable(wifiP2pDeviceList: Collection<WifiP2pDevice>) {
            log("onPeersAvailable :" + wifiP2pDeviceList.size)
            this@FileSenderActivity.wifiP2pDeviceList.clear()
            this@FileSenderActivity.wifiP2pDeviceList.addAll(wifiP2pDeviceList)
            deviceAdapter.notifyDataSetChanged()
            dismissLoadingDialog()
        }

        override fun onChannelDisconnected() {
            log("onChannelDisconnected")
        }
    }




    override fun onDestroy() {
        super.onDestroy()
        if (broadcastReceiver != null) {
            unregisterReceiver(broadcastReceiver)
        }
    }

    //通过点击事件选中群主（服务器端）设备，通过 connect 方法请求与之进行连接
    @SuppressLint("MissingPermission")
    private fun connect(wifiP2pDevice: WifiP2pDevice) {
        val wifiP2pConfig = WifiP2pConfig()
        wifiP2pConfig.deviceAddress = wifiP2pDevice.deviceAddress
        wifiP2pConfig.wps.setup = WpsInfo.PBC
        showLoadingDialog(message = "正在连接，deviceName: " + wifiP2pDevice.deviceName)
        showToast("正在连接，deviceName: " + wifiP2pDevice.deviceName)
        wifiP2pManager.connect(wifiP2pChannel, wifiP2pConfig,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("connect onSuccess")
                }

                override fun onFailure(reason: Int) {
                    showToast("连接失败 $reason")
                    dismissLoadingDialog()
                }
            })
    }

    private fun disconnect() {
        wifiP2pManager.cancelConnect(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onFailure(reasonCode: Int) {
                log("cancelConnect onFailure:$reasonCode")
            }

            override fun onSuccess() {
                log("cancelConnect onSuccess")
                tvConnectionStatus.text = null
                btnDisconnect.isEnabled = false
                btnChooseFile.isEnabled = false
            }
        })
        wifiP2pManager.removeGroup(wifiP2pChannel, null)
    }

    private fun log(log: String) {
        tvLog.append(log)
        tvLog.append("\n\n")
    }

    private fun clearLog() {
        tvLog.text = ""
    }

}