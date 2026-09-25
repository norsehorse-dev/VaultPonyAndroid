package dev.norsehorse.vaultpony.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.norsehorse.vaultpony.R

/** Masked unless the user has tapped the eye for this field. Every password
 *  field starts masked and returns to masked when its screen is left. */
fun revealTransformation(shown: Boolean): VisualTransformation =
    if (shown) VisualTransformation.None else PasswordVisualTransformation()

/** The eye button at the end of a password field. */
@Composable
fun RevealToggle(shown: Boolean, onToggle: (Boolean) -> Unit) {
    IconButton(onClick = { onToggle(!shown) }) {
        Icon(
            if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(
                if (shown) R.string.password_hide else R.string.password_show,
            ),
        )
    }
}
