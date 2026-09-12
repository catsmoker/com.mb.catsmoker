package com.catsmoker.app.system.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.theme.NdotFontFamily
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun StartupScreen(onFinished: () -> Unit) {
    // Warm up the Ndot font in background to prevent Dashboard transition lag
    // Using a non-zero size to ensure measurement occurs
    Box(modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f }) {
        Text(text = "Warmup", fontFamily = NdotFontFamily)
    }

    LaunchedEffect(Unit) {
        // Simulate initial loading/setup
        delay(1200.milliseconds) 
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Theme background, not fixed black: the red logo and dots read on both.
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(120.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            DotMatrixLoading()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                fontFamily = NdotFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun DotMatrixLoading() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val dotCount = 3
    
    val activeDot by infiniteTransition.animateValue(
        initialValue = 0,
        targetValue = dotCount,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "activeDot"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dotColor = Color.Red
        repeat(dotCount) { index ->
            val alpha = if (index <= activeDot) 1f else 0.2f
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(dotColor)
            )
        }
    }
}
