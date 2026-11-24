package com.hybridguard.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hybridguard.data.AlarmLevel

/** A STOP alarm over the lock screen: big, red, one OK button. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val status by Monitor.status.collectAsStateWithLifecycle()
            val top = status.alarms.top
            LaunchedEffect(top) { if (top == null) finish() }
            val stop = top?.level == AlarmLevel.STOP
            Column(
                Modifier.fillMaxSize().background(if (stop) Color(0xFFB71C1C) else Color(0xFFE65100)).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (stop) "STOP" else "CAUTION", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(16.dp))
                Text(top?.title ?: "", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(top?.advice ?: "", color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(top?.detail ?: "", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, textAlign = TextAlign.Center)
                status.alarms.alarms.drop(1).forEach {
                    Text("• " + it.title, color = Color.White, fontSize = 18.sp)
                }
                Spacer(Modifier.height(40.dp))
                Button(
                    onClick = { Monitor.acknowledge(); finish() },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) { Text("OK, seen", fontSize = 24.sp, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
