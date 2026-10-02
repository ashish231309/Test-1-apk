package com.ashishkumar.nivara.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ashishkumar.nivara.R
import com.ashishkumar.nivara.ui.components.NivaraPageHeader
import com.ashishkumar.nivara.ui.components.NivaraStatusCard
import com.ashishkumar.nivara.ui.components.NivaraStatusTone

/** Minimal home state model, ready to be connected to a real data source in a later stage. */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Ready : HomeUiState
    data class Error(val message: String) : HomeUiState
}

@Composable
fun HomeScreen(
    state: HomeUiState = HomeUiState.Ready,
    onRetry: () -> Unit = {},
) {
    Scaffold { contentPadding ->
        when (state) {
            HomeUiState.Loading -> LoadingContent(contentPadding)
            HomeUiState.Ready -> HomeContent(contentPadding)
            is HomeUiState.Error -> ErrorContent(
                message = state.message,
                contentPadding = contentPadding,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun HomeContent(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        NivaraPageHeader(
            title = stringResource(R.string.app_name),
            supportingText = stringResource(R.string.home_welcome),
        )
        NivaraStatusCard(
            title = "Your privacy workspace",
            message = "Review your security settings and existing privacy tools from Nivara Home.",
            tone = NivaraStatusTone.INFORMATION,
        )
    }
}

@Composable
private fun LoadingContent(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(
    message: String,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        NivaraStatusCard(
            title = "Could not load this screen",
            message = message,
            tone = NivaraStatusTone.ERROR,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
