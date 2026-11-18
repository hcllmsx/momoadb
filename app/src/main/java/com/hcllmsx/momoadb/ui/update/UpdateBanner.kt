package com.hcllmsx.momoadb.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcllmsx.momoadb.R
import com.hcllmsx.momoadb.update.UpdateCenter
import com.hcllmsx.momoadb.update.UpdateCheckState

/**
 * 全局更新横幅：启动静默检查发现新版本时，在任意界面顶部常驻展示，
 * 点击「前往下载」打开发布页，点 × 手动关闭；不关闭就一直显示。
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val banner by UpdateCenter.banner.collectAsStateWithLifecycle()

    // 退场动画期间 banner 已变为 null，缓存最近一次可用更新供内容渲染。
    val lastShown = remember { mutableStateOf<UpdateCheckState.Available?>(null) }
    banner?.let { lastShown.value = it }
    val current = lastShown.value
    val uriHandler = LocalUriHandler.current

    AnimatedVisibility(
        visible = banner != null && current != null,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(
                        R.string.update_banner_title,
                        current?.latestVersion.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { current?.let { uriHandler.openUri(it.releaseUrl) } }) {
                    Text(text = stringResource(R.string.update_banner_download))
                }
                IconButton(onClick = UpdateCenter::dismissBanner) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.update_banner_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
