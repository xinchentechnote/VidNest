package com.vidnest.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vidnest.app.domain.model.VideoSortField

/** 排序菜单按钮（文件夹页 / 全部视频页共用） */
@Composable
fun SortMenuButton(
    current: VideoSortField,
    onSelect: (VideoSortField) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.Sort, contentDescription = "排序方式")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        VideoSortField.entries.forEach { field ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = field.label,
                        color = if (field == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                onClick = {
                    expanded = false
                    onSelect(field)
                },
            )
        }
    }
}
