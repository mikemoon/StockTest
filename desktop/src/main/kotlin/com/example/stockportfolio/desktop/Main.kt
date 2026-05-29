package com.example.stockportfolio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DecimalFormat
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

private const val DATABASE_ROOT = "https://stockportfolio-7a2ba-default-rtdb.firebaseio.com"
private const val SYNC_PATH = "stockPortfolio/sharedLists/default"
private const val MAX_SUGGESTIONS = 5

private val amountFormat = DecimalFormat("#,##0.##")
private val commonStocks = listOf(
    StockSuggestion("005930", "삼성전자", "KOSPI", Market.DOMESTIC),
    StockSuggestion("009150", "삼성전기", "KOSPI", Market.DOMESTIC),
    StockSuggestion("000660", "SK하이닉스", "KOSPI", Market.DOMESTIC),
    StockSuggestion("373220", "LG에너지솔루션", "KOSPI", Market.DOMESTIC),
    StockSuggestion("005380", "현대차", "KOSPI", Market.DOMESTIC),
    StockSuggestion("000270", "기아", "KOSPI", Market.DOMESTIC),
    StockSuggestion("035420", "NAVER", "KOSPI", Market.DOMESTIC),
    StockSuggestion("035720", "카카오", "KOSPI", Market.DOMESTIC),
    StockSuggestion("144600", "KODEX 은선물(H)", "KOSPI ETF", Market.DOMESTIC),
    StockSuggestion("0172V0", "1Q 은액티브", "KOSPI ETF", Market.DOMESTIC),
    StockSuggestion("AAPL", "Apple Inc.", "Nasdaq", Market.OVERSEAS),
    StockSuggestion("MSFT", "Microsoft Corporation", "Nasdaq", Market.OVERSEAS),
    StockSuggestion("TSLA", "Tesla, Inc.", "Nasdaq", Market.OVERSEAS),
    StockSuggestion("NVDA", "NVIDIA Corporation", "Nasdaq", Market.OVERSEAS),
    StockSuggestion("GOOGL", "Alphabet Inc.", "Nasdaq", Market.OVERSEAS),
)

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "StockPortfolio") {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F9F7)) {
                StockPortfolioDesktopApp()
            }
        }
    }
}

@Composable
private fun StockPortfolioDesktopApp() {
    val portfolioItems = remember { mutableStateListOf<StockItem>() }
    val watchItems = remember { mutableStateListOf<WatchItem>() }
    var screen by remember { mutableStateOf(Screen.PORTFOLIO) }
    var market by remember { mutableStateOf(Market.DOMESTIC) }
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var purchasePrice by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Firebase 동기화 대기 중") }
    var refreshing by remember { mutableStateOf(false) }

    fun loadRemote() {
        thread {
            try {
                val remote = fetchRemoteLists()
                portfolioItems.clear()
                portfolioItems.addAll(parsePortfolioItems(remote.portfolioJson))
                watchItems.clear()
                watchItems.addAll(parseWatchItems(remote.watchJson))
                message = "Firebase 목록을 불러왔습니다."
            } catch (e: Exception) {
                message = "Firebase 목록을 불러오지 못했습니다."
            }
        }
    }

    fun saveRemote() {
        thread {
            try {
                saveRemoteLists(serializePortfolioItems(portfolioItems), serializeWatchItems(watchItems))
                message = "Firebase에 저장했습니다."
            } catch (e: Exception) {
                message = "Firebase 저장에 실패했습니다."
            }
        }
    }

    fun refreshPrices() {
        if (refreshing) return
        refreshing = true
        message = "현재가 갱신 중"
        thread {
            portfolioItems.forEachIndexed { index, item ->
                portfolioItems[index] = item.copy(currentPrice = runCatching { fetchCurrentPrice(item.name, item.market) }.getOrNull())
            }
            watchItems.forEachIndexed { index, item ->
                watchItems[index] = item.copy(currentPrice = runCatching { fetchCurrentPrice(item.name, item.market) }.getOrNull())
            }
            refreshing = false
            message = "현재가를 갱신했습니다."
            saveRemote()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        loadRemote()
    }

    val suggestions = remember(name, market) {
        if (name.trim().length < 2) emptyList() else findLocalSuggestions(name, market)
    }
    val visiblePortfolioItems = portfolioItems.filter { it.market == market }
    val visibleWatchItems = watchItems.filter { it.market == market }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("주식 목록", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF18231E), modifier = Modifier.weight(1f))
            Button(onClick = { refreshPrices() }, enabled = !refreshing, colors = quietButtonColors()) {
                Text(if (refreshing) "갱신 중" else "API 갱신")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { loadRemote() }, colors = quietButtonColors()) { Text("동기화") }
        }

        Text(summaryText(screen, market, visiblePortfolioItems, visibleWatchItems), color = Color(0xFF4A5650))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentButton("보유종목", screen == Screen.PORTFOLIO, Modifier.weight(1f)) { screen = Screen.PORTFOLIO }
            SegmentButton("관심종목", screen == Screen.WATCHLIST, Modifier.weight(1f)) { screen = Screen.WATCHLIST }
        }

        Card(backgroundColor = Color.White, elevation = 0.dp, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentButton("국내", market == Market.DOMESTIC, Modifier.weight(1f)) {
                        market = Market.DOMESTIC
                        name = ""
                    }
                    SegmentButton("해외", market == Market.OVERSEAS, Modifier.weight(1f)) {
                        market = Market.OVERSEAS
                        name = ""
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(if (market == Market.DOMESTIC) "종목명 또는 종목코드" else "종목명 또는 티커") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (suggestions.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().background(Color(0xFFFAFCFA))) {
                        suggestions.forEach { suggestion ->
                            Text(
                                suggestion.displayText(),
                                modifier = Modifier.fillMaxWidth().clickable { name = suggestion.inputText() }.padding(12.dp),
                                color = Color(0xFF18231E),
                            )
                        }
                    }
                } else if (name.trim().length >= 2) {
                    Text("검색 결과 없음", modifier = Modifier.fillMaxWidth().background(Color(0xFFFAFCFA)).padding(12.dp), color = Color(0xFF58645E))
                }

                if (screen == Screen.PORTFOLIO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(quantity, { quantity = it }, label = { Text("수량") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(purchasePrice, { purchasePrice = it }, label = { Text("구매가(1주당)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }

                Button(
                    onClick = {
                        val inputName = name.trim()
                        if (inputName.isEmpty()) {
                            message = "종목명을 입력하세요."
                            return@Button
                        }
                        if (screen == Screen.PORTFOLIO) {
                            val q = quantity.toDoubleOrNull()
                            val p = purchasePrice.toDoubleOrNull()
                            if (q == null || p == null || q <= 0 || p < 0) {
                                message = "수량과 구매가를 올바르게 입력하세요."
                                return@Button
                            }
                            portfolioItems.add(StockItem(inputName, q, p, market))
                            quantity = ""
                            purchasePrice = ""
                        } else {
                            if (watchItems.any { it.market == market && it.name.equals(inputName, ignoreCase = true) }) {
                                message = "이미 등록된 관심종목입니다."
                                return@Button
                            }
                            watchItems.add(WatchItem(inputName, market))
                        }
                        name = ""
                        saveRemote()
                        refreshPrices()
                    },
                    colors = primaryButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (screen == Screen.PORTFOLIO) "+ 종목 추가" else "+ 관심 등록")
                }
            }
        }

        Text(message, color = Color(0xFF58645E), fontSize = 13.sp)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (screen == Screen.PORTFOLIO) {
                if (visiblePortfolioItems.isEmpty()) {
                    item { EmptyText(if (market == Market.DOMESTIC) "아직 추가한 국내 주식이 없습니다." else "아직 추가한 해외 주식이 없습니다.") }
                }
                items(visiblePortfolioItems) { item ->
                    StockCard(item, onDelete = {
                        portfolioItems.remove(item)
                        saveRemote()
                    })
                }
            } else {
                if (visibleWatchItems.isEmpty()) {
                    item { EmptyText(if (market == Market.DOMESTIC) "아직 등록한 국내 관심종목이 없습니다." else "아직 등록한 해외 관심종목이 없습니다.") }
                }
                items(visibleWatchItems) { item ->
                    WatchCard(item, onDelete = {
                        watchItems.remove(item)
                        saveRemote()
                    })
                }
            }
        }
    }
}

@Composable
private fun SegmentButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, colors = if (selected) primaryButtonColors() else quietButtonColors(), modifier = modifier.height(42.dp)) {
        Text(text)
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(32.dp), color = Color(0xFF58645E))
}

@Composable
private fun StockCard(item: StockItem, onDelete: () -> Unit) {
    val currentValue = (item.currentPrice ?: 0.0) * item.quantity
    val profit = currentValue - item.purchaseAmount()
    Card(backgroundColor = Color.White, elevation = 0.dp, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFF18231E), modifier = Modifier.weight(1f))
                Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF7E6E3), contentColor = Color(0xFF96291F))) { Text("삭제") }
            }
            InfoLine("구분", if (item.market == Market.DOMESTIC) "국내" else "해외")
            InfoLine("수량", "${amountFormat.format(item.quantity)}주")
            InfoLine("구매가", formatMoney(item.purchasePrice, item.market))
            InfoLine("구매금액", formatMoney(item.purchaseAmount(), item.market))
            InfoLine("현재가", item.currentPrice?.let { formatMoney(it, item.market) } ?: "불러오는 중")
            Text(
                "평가금액 / 손익: " + (item.currentPrice?.let { "${formatMoney(currentValue, item.market)} / ${signedMoney(profit, item.market)}" } ?: "-"),
                color = if (item.currentPrice == null) Color(0xFF414C46) else if (profit >= 0) Color(0xFFCC2F2A) else Color(0xFF265FB5),
            )
        }
    }
}

@Composable
private fun WatchCard(item: WatchItem, onDelete: () -> Unit) {
    Card(backgroundColor = Color.White, elevation = 0.dp, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFF18231E), modifier = Modifier.weight(1f))
                Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF7E6E3), contentColor = Color(0xFF96291F))) { Text("삭제") }
            }
            InfoLine("구분", if (item.market == Market.DOMESTIC) "국내" else "해외")
            InfoLine("현재가", item.currentPrice?.let { formatMoney(it, item.market) } ?: "불러오는 중")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label: $value", color = Color(0xFF414C46), fontSize = 15.sp)
}

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1E7A5F), contentColor = Color.White)

@Composable
private fun quietButtonColors() = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFEBF1EE), contentColor = Color(0xFF18231E))

private fun summaryText(screen: Screen, market: Market, portfolioItems: List<StockItem>, watchItems: List<WatchItem>): String {
    if (screen == Screen.WATCHLIST) {
        return "${market.label()} 관심종목 ${watchItems.size}개"
    }
    val invested = portfolioItems.sumOf { it.purchaseAmount() }
    val currentValue = portfolioItems.sumOf { (it.currentPrice ?: 0.0) * it.quantity }
    return "${market.label()} ${portfolioItems.size}개 | 구매금액 ${formatMoney(invested, market)} | 현재 평가 ${formatMoney(currentValue, market)}"
}

private fun findLocalSuggestions(query: String, market: Market): List<StockSuggestion> {
    val normalized = normalizeSearchText(query)
    return commonStocks.filter {
        it.market == market && (normalizeSearchText(it.name).contains(normalized) || normalizeSearchText(it.symbol).contains(normalized))
    }.take(MAX_SUGGESTIONS)
}

private fun fetchRemoteLists(): RemoteLists {
    val body = httpRequest("$DATABASE_ROOT/$SYNC_PATH.json", "GET")
    return RemoteLists(
        portfolioJson = readJsonStringField(body, "portfolioJson") ?: "[]",
        watchJson = readJsonStringField(body, "watchJson") ?: "[]",
    )
}

private fun saveRemoteLists(portfolioJson: String, watchJson: String) {
    val body = """{"portfolioJson":${quoteJson(portfolioJson)},"watchJson":${quoteJson(watchJson)}}"""
    httpRequest("$DATABASE_ROOT/$SYNC_PATH.json", "PUT", body)
}

private fun fetchCurrentPrice(name: String, market: Market): Double? {
    return if (market == Market.DOMESTIC) fetchDomesticCurrentPrice(name) else fetchOverseasCurrentPrice(name)
}

private fun fetchDomesticCurrentPrice(stockName: String): Double? {
    var code = normalizeDomesticCode(extractSymbol(stockName))
    if (!isDomesticCode(code)) {
        code = findLocalSuggestions(code, Market.DOMESTIC).firstOrNull()?.symbol.orEmpty()
    }
    if (!isDomesticCode(code)) return null
    val body = httpRequest("https://m.stock.naver.com/api/stock/$code/basic", "GET")
    return listOf("closePrice", "stockEndPrice", "nowVal", "currentPrice", "price")
        .firstNotNullOfOrNull { parsePrice(readJsonStringField(body, it)) }
}

private fun fetchOverseasCurrentPrice(stockName: String): Double? {
    val symbol = extractSymbol(stockName)
    val encoded = URLEncoder.encode(symbol, "UTF-8")
    val body = httpRequest("https://query1.finance.yahoo.com/v8/finance/chart/$encoded?range=1d&interval=1d", "GET")
    return Regex(""""regularMarketPrice"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        ?: Regex(""""previousClose"\s*:\s*([0-9.]+)""").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
}

private fun httpRequest(urlText: String, method: String, body: String? = null): String {
    val connection = URL(urlText).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    connection.setRequestProperty("Accept", "application/json")
    if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
    }
    try {
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            return buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
        }
    } finally {
        connection.disconnect()
    }
}

private fun serializePortfolioItems(items: List<StockItem>): String {
    return items.joinToString(prefix = "[", postfix = "]") {
        """{"name":${quoteJson(it.name)},"quantity":${it.quantity},"purchasePrice":${it.purchasePrice},"totalPrice":${it.purchaseAmount()},"market":${quoteJson(it.market.name)},"currentPrice":${it.currentPrice ?: "null"}}"""
    }
}

private fun serializeWatchItems(items: List<WatchItem>): String {
    return items.joinToString(prefix = "[", postfix = "]") {
        """{"name":${quoteJson(it.name)},"market":${quoteJson(it.market.name)},"currentPrice":${it.currentPrice ?: "null"}}"""
    }
}

private fun parsePortfolioItems(json: String): List<StockItem> {
    return splitObjects(json).mapNotNull { obj ->
        val name = readJsonStringField(obj, "name") ?: return@mapNotNull null
        val market = Market.valueOf(readJsonStringField(obj, "market") ?: "DOMESTIC")
        StockItem(
            name = name,
            quantity = readJsonNumberField(obj, "quantity") ?: return@mapNotNull null,
            purchasePrice = readJsonNumberField(obj, "purchasePrice") ?: readJsonNumberField(obj, "totalPrice") ?: return@mapNotNull null,
            market = market,
            currentPrice = readJsonNumberField(obj, "currentPrice"),
        )
    }
}

private fun parseWatchItems(json: String): List<WatchItem> {
    return splitObjects(json).mapNotNull { obj ->
        val name = readJsonStringField(obj, "name") ?: return@mapNotNull null
        val market = Market.valueOf(readJsonStringField(obj, "market") ?: "DOMESTIC")
        WatchItem(name = name, market = market, currentPrice = readJsonNumberField(obj, "currentPrice"))
    }
}

private fun splitObjects(json: String): List<String> {
    val result = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    json.forEachIndexed { index, char ->
        if (escaped) {
            escaped = false
            return@forEachIndexed
        }
        if (char == '\\') {
            escaped = true
            return@forEachIndexed
        }
        if (char == '"') inString = !inString
        if (!inString) {
            if (char == '{') {
                if (depth == 0) start = index
                depth++
            } else if (char == '}') {
                depth--
                if (depth == 0 && start >= 0) result.add(json.substring(start, index + 1))
            }
        }
    }
    return result
}

private fun readJsonStringField(json: String, key: String): String? {
    val match = Regex(""""${Regex.escape(key)}"\s*:\s*"(.*?)"""").find(json) ?: return null
    return unescapeJson(match.groupValues[1])
}

private fun readJsonNumberField(json: String, key: String): Double? {
    return Regex(""""${Regex.escape(key)}"\s*:\s*(-?[0-9.]+|null)""").find(json)?.groupValues?.get(1)?.takeIf { it != "null" }?.toDoubleOrNull()
}

private fun quoteJson(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

private fun unescapeJson(value: String): String {
    return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun extractSymbol(input: String): String {
    val open = input.lastIndexOf('(')
    val close = input.lastIndexOf(')')
    return if (open >= 0 && close > open) input.substring(open + 1, close).trim() else input
}

private fun normalizeDomesticCode(raw: String?): String {
    var code = raw?.trim().orEmpty()
    if (code.endsWith(".KS") || code.endsWith(".KQ")) code = code.dropLast(3)
    return code.uppercase(Locale.ROOT)
}

private fun isDomesticCode(code: String?): Boolean = code?.uppercase(Locale.ROOT)?.matches(Regex("[0-9A-Z]{6}")) == true

private fun normalizeSearchText(text: String): String {
    return text.lowercase(Locale.ROOT).replace(" ", "").replace("(", "").replace(")", "").replace("-", "").replace("_", "").replace("/", "")
}

private fun parsePrice(raw: String?): Double? = raw?.takeIf { it.isNotBlank() }?.replace(",", "")?.trim()?.toDoubleOrNull()

private fun formatMoney(value: Double, market: Market): String = if (market == Market.OVERSEAS) "$${amountFormat.format(value)}" else "${amountFormat.format(value)}원"

private fun signedMoney(value: Double, market: Market): String {
    return when {
        value > 0 -> "+${formatMoney(value, market)}"
        value < 0 -> "-${formatMoney(abs(value), market)}"
        else -> formatMoney(value, market)
    }
}

private fun Market.label() = if (this == Market.DOMESTIC) "국내" else "해외"

private enum class Market {
    DOMESTIC,
    OVERSEAS,
}

private enum class Screen {
    PORTFOLIO,
    WATCHLIST,
}

private data class StockItem(
    val name: String,
    val quantity: Double,
    val purchasePrice: Double,
    val market: Market,
    val currentPrice: Double? = null,
) {
    fun purchaseAmount(): Double = purchasePrice * quantity
}

private data class WatchItem(
    val name: String,
    val market: Market,
    val currentPrice: Double? = null,
)

private data class StockSuggestion(
    val symbol: String,
    val name: String,
    val exchange: String,
    val market: Market,
) {
    fun displayText(): String = name + " (" + symbol + ")" + if (exchange.isEmpty()) "" else " · $exchange"
    fun inputText(): String = "$name ($symbol)"
}

private data class RemoteLists(
    val portfolioJson: String,
    val watchJson: String,
)
