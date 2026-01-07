package io.github.tiper.sample

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.tiper.sample.presentation.SampleScreenAndroid
import io.github.tiper.sample.presentation.SampleScreenCommon
import io.github.tiper.sample.presentation.SampleViewModel
import io.github.tiper.sample.ui.theme.MyApplicationTheme
import io.github.tiper.sample.aidl1.IMyAidlService as IMyAidlService1
import io.github.tiper.sample.aidl2.IMyAidlService as IMyAidlService2
import io.github.tiper.sample.jni2.NativeLib as NativeLib2

class MainActivity : ComponentActivity() {

    private var myAidlService1: IMyAidlService1? = null

    private var myAidlService2: IMyAidlService2? = null

    // Service connection for AIDL Service 1
    private val connection1 = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            myAidlService1 = IMyAidlService1.Stub.asInterface(binder)
            Log.d("AIDL Client", "SAMPLE: Service connected. Message: ${myAidlService1?.message}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            myAidlService1 = null
            Log.d("AIDL Client", "SAMPLE: onServiceDisconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Log.d("AIDL Client", "SAMPLE: onBindingDied")
        }

        override fun onNullBinding(name: ComponentName?) {
            super.onNullBinding(name)
            Log.d("AIDL Client", "SAMPLE: onNullBinding")
        }
    }

    // Service connection for AIDL Service 2
    private val connection2 = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            myAidlService2 = IMyAidlService2.Stub.asInterface(binder)
            Log.d("AIDL Client", "SAMPLE: Service connected. Message: ${myAidlService2?.message}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            myAidlService2 = null
            Log.d("AIDL Client", "SAMPLE: onServiceDisconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Log.d("AIDL Client", "SAMPLE: onBindingDied")
        }

        override fun onNullBinding(name: ComponentName?) {
            super.onNullBinding(name)
            Log.d("AIDL Client", "SAMPLE: onNullBinding")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Native Library Example
        val result2 = NativeLib2.subtractNumbers(5, 7)
        Log.d("Native", "SAMPLE: Result of 5 - 7 = $result2")

        // Bind to AIDL Service 1
        val intent1 = Intent("io.github.tiper.sample.aidl1.IMyAidlService").apply {
            setPackage("io.github.tiper.sample")
        }
        bindService(intent1, connection1, BIND_AUTO_CREATE)

        // Bind to AIDL Service 2
        val intent2 = Intent("io.github.tiper.sample.aidl2.IMyAidlService").apply {
            setPackage("io.github.tiper.sample")
        }
        bindService(intent2, connection2, BIND_AUTO_CREATE)

        setContent {
            BackHandler { }
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        SampleViewModel()
                        SampleScreenAndroid()
                        SampleScreenCommon()
                        Greeting(name = "Android")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection1)
        unbindService(connection2)
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        Greeting("Android")
    }
}
