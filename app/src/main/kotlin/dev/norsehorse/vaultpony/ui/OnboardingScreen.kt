package dev.norsehorse.vaultpony.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Check
import dev.norsehorse.vaultpony.R
import dev.norsehorse.vaultpony.i18n.LanguageState
import dev.norsehorse.vaultpony.i18n.LocaleManager
import dev.norsehorse.vaultpony.i18n.SupportedLanguage
import kotlinx.coroutines.launch

private enum class SlideKind { PLAIN, LANGUAGE, CREATE }

private data class OnbSlide(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    val kind: SlideKind,
)

private val SLIDES = listOf(
    OnbSlide(Icons.Filled.Language, R.string.onboarding_language_title, R.string.onboarding_language_body, SlideKind.LANGUAGE),
    OnbSlide(Icons.Filled.Lock, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body, SlideKind.PLAIN),
    OnbSlide(Icons.Filled.Add, R.string.onboarding_create_title, R.string.onboarding_create_body, SlideKind.CREATE),
    OnbSlide(Icons.Filled.VisibilityOff, R.string.onboarding_hidden_title, R.string.onboarding_hidden_body, SlideKind.PLAIN),
    OnbSlide(Icons.Filled.Fingerprint, R.string.onboarding_security_title, R.string.onboarding_security_body, SlideKind.PLAIN),
)

/**
 * First-run onboarding carousel. Slide 0 picks the language (live), a later
 * slide launches real vault creation, and the last slide finishes. Both the
 * create CTA and finishing mark onboarding complete via the callbacks.
 */
@Composable
fun OnboardingScreen(
    startPage: Int = 0,
    onCreateVault: () -> Unit,
    onFinish: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = startPage.coerceIn(0, SLIDES.lastIndex),
        pageCount = { SLIDES.size },
    )
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == SLIDES.lastIndex

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (!isLast) {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            OnboardingPage(SLIDES[page], onCreateVault)
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SLIDES.forEachIndexed { index, _ ->
                    val active = pagerState.currentPage == index
                    Box(
                        Modifier
                            .size(if (active) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            ),
                    )
                }
            }
            Button(
                onClick = {
                    if (isLast) onFinish()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
            ) {
                Text(
                    if (isLast) stringResource(R.string.onboarding_get_started)
                    else stringResource(R.string.onboarding_next),
                )
            }
        }
    }
}

@Composable
private fun OnboardingPage(slide: OnbSlide, onCreateVault: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            slide.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(slide.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(slide.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        when (slide.kind) {
            SlideKind.LANGUAGE -> LanguagePicker()
            SlideKind.CREATE -> Button(onClick = onCreateVault, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_create_cta))
            }
            SlideKind.PLAIN -> {}
        }
    }
}

@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val currentLang by LanguageState.current
    Column(Modifier.fillMaxWidth()) {
        for (lang in SupportedLanguage.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { LocaleManager.setLanguage(context, lang) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(lang.nativeName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (lang.tag == currentLang) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
