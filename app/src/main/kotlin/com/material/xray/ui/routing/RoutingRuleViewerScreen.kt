package com.material.xray.ui.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.material.xray.R
import com.material.xray.ui.configviewer.JsonTokenKind
import com.material.xray.ui.configviewer.tokenizeJsonLines
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class RoutingRuleViewerRequest(
    val name: String,
    val domains: List<String>,
    val ips: List<String>,
    val port: String?,
    val protocols: List<String>,
    val targetKind: RoutingRuleViewerTargetKind?,
    val targetTag: String?,
    val additionalConditionFields: List<String> = emptyList(),
    val rawJson: String? = null,
)

@Serializable
enum class RoutingRuleViewerTargetKind {
    Outbound,
    Balancer,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutingRuleViewerScreen(request: RoutingRuleViewerRequest, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(request.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.routing_rule_viewer_back),
                        )
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(contentType = "ruleSummary") {
                RuleSummary(request)
            }
            request.rawJson?.let { rawJson ->
                item(contentType = "jsonTitle") {
                    Text(
                        text = stringResource(R.string.routing_rule_raw_json),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item(contentType = "json") {
                    HighlightedJson(rawJson)
                }
            }
        }
    }
}

@Composable
private fun RuleSummary(request: RoutingRuleViewerRequest) {
    val content = fullRuleContentText(request)
    val target = when (request.targetKind) {
        RoutingRuleViewerTargetKind.Outbound -> request.targetTag?.let {
            stringResource(R.string.routing_rule_target_outbound, it)
        }
        RoutingRuleViewerTargetKind.Balancer -> request.targetTag?.let {
            stringResource(R.string.routing_rule_target_balancer, it)
        }
        null -> null
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(content)
            target?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            if (request.additionalConditionFields.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.routing_rule_additional_conditions,
                        request.additionalConditionFields.joinToString(", "),
                    ),
                )
            }
        }
    }
}

@Composable
private fun fullRuleContentText(request: RoutingRuleViewerRequest): String {
    val domains = routingDomainDisplayValues(request.domains)
    val ips = request.ips.map(String::trim).filter(String::isNotEmpty)
    val protocols = request.protocols.map(String::trim).filter(String::isNotEmpty)
    val domainText = domains.takeIf(List<String>::isNotEmpty)?.let {
        pluralStringResource(R.plurals.routing_rule_domains, it.size, it.joinToString(", "))
    }
    val ipText = ips.takeIf(List<String>::isNotEmpty)?.let {
        pluralStringResource(R.plurals.routing_rule_ips, it.size, it.joinToString(", "))
    }
    val portText = request.port?.takeIf(String::isNotBlank)?.let {
        stringResource(R.string.routing_rule_port, it)
    }
    val protocolText = protocols.takeIf(List<String>::isNotEmpty)?.let {
        pluralStringResource(R.plurals.routing_rule_protocols, it.size, it.joinToString(", "))
    }
    return listOfNotNull(domainText, ipText, portText, protocolText)
        .joinToString("\n")
        .ifBlank { stringResource(R.string.routing_no_match_content) }
}

@Composable
private fun HighlightedJson(rawJson: String) {
    val formattedJson = remember(rawJson) { rawJson.prettyPrintedOrSelf() }
    val colors = rememberJsonSyntaxColors()
    val highlighted = remember(formattedJson, colors) {
        buildAnnotatedString {
            tokenizeJsonLines(formattedJson).forEachIndexed { lineIndex, tokens ->
                if (lineIndex > 0) append('\n')
                tokens.forEach { token ->
                    val color = colors.colorFor(token.kind)
                    if (color == null) append(token.text) else withStyle(SpanStyle(color = color)) { append(token.text) }
                }
            }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                text = highlighted,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
                color = colors.plain,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                softWrap = false,
            )
        }
    }
}

private fun String.prettyPrintedOrSelf(): String = runCatching {
    val element = Json.parseToJsonElement(this)
    PrettyJson.encodeToString(JsonElement.serializer(), element)
}.getOrDefault(this)

@Composable
private fun rememberJsonSyntaxColors(): JsonSyntaxColors {
    val dark = isSystemInDarkTheme()
    return remember(dark) { if (dark) DarkJsonSyntaxColors else LightJsonSyntaxColors }
}

private data class JsonSyntaxColors(
    val key: Color,
    val stringValue: Color,
    val number: Color,
    val literal: Color,
    val punctuation: Color,
    val plain: Color,
) {
    fun colorFor(kind: JsonTokenKind): Color? = when (kind) {
        JsonTokenKind.Key -> key
        JsonTokenKind.StringValue -> stringValue
        JsonTokenKind.Number -> number
        JsonTokenKind.Literal -> literal
        JsonTokenKind.Punctuation -> punctuation
        JsonTokenKind.Plain -> null
    }
}

private val PrettyJson = Json { prettyPrint = true }

private val LightJsonSyntaxColors = JsonSyntaxColors(
    key = Color(0xFF0B57D0),
    stringValue = Color(0xFF1B7F3B),
    number = Color(0xFFA6412A),
    literal = Color(0xFF7A3E9D),
    punctuation = Color(0xFF6B6B6B),
    plain = Color(0xFF1F1F1F),
)

private val DarkJsonSyntaxColors = JsonSyntaxColors(
    key = Color(0xFF8AB4F8),
    stringValue = Color(0xFF7EC699),
    number = Color(0xFFE8A87C),
    literal = Color(0xFFC792EA),
    punctuation = Color(0xFF9AA0A6),
    plain = Color(0xFFE3E3E3),
)
