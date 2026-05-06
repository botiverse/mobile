package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.R
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockTheme

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SlockBasicComponentsPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SlockLogoBadge(
                text = "Slock Android Workspace With Long Server Name",
                showDropdown = true,
                statusColor = SlockColor.AccentLime
            )
            SlockLogoBadge(
                text = "S",
                variant = SlockLogoBadgeVariant.Compact,
                statusColor = SlockColor.AccentPink
            )

            Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                SlockButton(
                    text = "Sign in",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                )
                SlockButton(
                    text = "Continue",
                    onClick = {},
                    variant = SlockButtonVariant.Yellow,
                    modifier = Modifier.fillMaxWidth()
                )
                SlockButton(
                    text = "Loading",
                    onClick = {},
                    loading = true,
                    variant = SlockButtonVariant.Cyan,
                    modifier = Modifier.fillMaxWidth()
                )
                SlockButton(
                    text = "Disabled",
                    onClick = {},
                    enabled = false,
                    variant = SlockButtonVariant.Outline,
                    modifier = Modifier.fillMaxWidth()
                )
                SlockButton(
                    text = "GitHub",
                    onClick = {},
                    variant = SlockButtonVariant.Black,
                    modifier = Modifier.fillMaxWidth()
                )
                SlockButton(
                    text = "Continue with Google",
                    onClick = {},
                    variant = SlockButtonVariant.Outline,
                    leadingContent = {
                        Image(
                            painter = painterResource(R.drawable.ic_google),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                SlockButton(
                    text = "Continue with GitHub",
                    onClick = {},
                    variant = SlockButtonVariant.Black,
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = null,
                            tint = SlockColor.SurfaceWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val normalValue = remember { mutableStateOf("hello@slock.ai") }
            val emptyValue = remember { mutableStateOf("") }
            SlockTextField(
                value = normalValue.value,
                onValueChange = { normalValue.value = it },
                label = "Email",
                placeholder = "you@example.com"
            )
            SlockTextField(
                value = "focused@slock.ai",
                onValueChange = {},
                label = "Email focused",
                placeholder = "you@example.com",
                forceFocused = true
            )
            SlockTextField(
                value = emptyValue.value,
                onValueChange = { emptyValue.value = it },
                label = "Password",
                placeholder = "Password",
                isError = true,
                errorText = "Password is required"
            )
            SlockTextField(
                value = "Read only",
                onValueChange = {},
                label = "Disabled",
                enabled = false
            )

            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
                SlockIconSquareButton(
                    icon = Icons.Default.Home,
                    contentDescription = "Home",
                    onClick = {},
                    selected = true
                )
                SlockIconSquareButton(
                    icon = Icons.Default.Favorite,
                    contentDescription = "Favorite",
                    onClick = {}
                )
                SlockIconSquareButton(
                    icon = Icons.Default.AccountBox,
                    contentDescription = "Account",
                    onClick = {},
                    enabled = false
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
                SlockCheckbox(checked = false)
                SlockCheckbox(checked = true)
                SlockCheckbox(checked = false, enabled = false)
                SlockCheckbox(checked = true, enabled = false)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLabeledCheckbox(
                    label = "As Task",
                    checked = true,
                    onCheckedChange = {}
                )
                SlockToggle(checked = false, onCheckedChange = {})
                SlockToggle(checked = true, onCheckedChange = {})
                SlockToggle(checked = true, onCheckedChange = {}, enabled = false)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockRadio(selected = false, onSelectedChange = {})
                SlockRadio(selected = true, onSelectedChange = {})
                SlockRadio(selected = true, onSelectedChange = {}, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                SlockSegmentOption("All", selected = true, onSelected = {})
                SlockSegmentOption("Unread", selected = false, onSelected = {})
                SlockSegmentOption("Done", selected = false, onSelected = {}, enabled = false)
            }
        }
    }
}
