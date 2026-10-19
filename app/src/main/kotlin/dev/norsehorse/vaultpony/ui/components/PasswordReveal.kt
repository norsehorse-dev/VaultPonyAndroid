package dev.norsehorse.vaultpony.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.norsehorse.vaultpony.R

/** Masked unless the user has tapped the eye for this field. Every password
 *  field starts masked and returns to masked when its screen is left. */
fun revealTransformation(shown: Boolean): VisualTransformation =
    if (shown) VisualTransformation.None else PasswordVisualTransformation()

/** Tags a password field for password managers (Compose 1.8 autofill, plan
 *  §2.4). [new] marks a password being set, so a manager offers to generate
 *  or save one instead of filling an old one. PIM fields are never tagged. */
fun Modifier.passwordAutofill(new: Boolean = false): Modifier =
    semantics { contentType = if (new) ContentType.NewPassword else ContentType.Password }

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
