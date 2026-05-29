package com.example.stockportfolio

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DecimalFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        const val PREFS_NAME = "stock_portfolio"
        const val KEY_ITEMS = "items"
        const val KEY_WATCH_ITEMS = "watch_items"
        const val MAX_SUGGESTIONS = 5
        const val SEARCH_DELAY_MS = 350L
        const val AUTO_REFRESH_INTERVAL_MS = 60_000L
    }

    private val items = mutableListOf<StockItem>()
    private val watchItems = mutableListOf<WatchItem>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val searchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val amountFormat = DecimalFormat("#,##0.##")
    private val commonStocks = arrayOf(
        StockSuggestion("005930", "삼성전자", "KOSPI", Market.DOMESTIC),
        StockSuggestion("009150", "삼성전기", "KOSPI", Market.DOMESTIC),
        StockSuggestion("000660", "SK하이닉스", "KOSPI", Market.DOMESTIC),
        StockSuggestion("373220", "LG에너지솔루션", "KOSPI", Market.DOMESTIC),
        StockSuggestion("005380", "현대차", "KOSPI", Market.DOMESTIC),
        StockSuggestion("000270", "기아", "KOSPI", Market.DOMESTIC),
        StockSuggestion("035420", "NAVER", "KOSPI", Market.DOMESTIC),
        StockSuggestion("035720", "카카오", "KOSPI", Market.DOMESTIC),
        StockSuggestion("207940", "삼성바이오로직스", "KOSPI", Market.DOMESTIC),
        StockSuggestion("051910", "LG화학", "KOSPI", Market.DOMESTIC),
        StockSuggestion("006400", "삼성SDI", "KOSPI", Market.DOMESTIC),
        StockSuggestion("068270", "셀트리온", "KOSPI", Market.DOMESTIC),
        StockSuggestion("005490", "POSCO홀딩스", "KOSPI", Market.DOMESTIC),
        StockSuggestion("105560", "KB금융", "KOSPI", Market.DOMESTIC),
        StockSuggestion("055550", "신한지주", "KOSPI", Market.DOMESTIC),
        StockSuggestion("012330", "현대모비스", "KOSPI", Market.DOMESTIC),
        StockSuggestion("144600", "KODEX 은선물(H)", "KOSPI ETF", Market.DOMESTIC),
        StockSuggestion("0172V0", "1Q 은액티브", "KOSPI ETF", Market.DOMESTIC),
        StockSuggestion("AAPL", "Apple Inc.", "Nasdaq", Market.OVERSEAS),
        StockSuggestion("MSFT", "Microsoft Corporation", "Nasdaq", Market.OVERSEAS),
        StockSuggestion("TSLA", "Tesla, Inc.", "Nasdaq", Market.OVERSEAS),
        StockSuggestion("NVDA", "NVIDIA Corporation", "Nasdaq", Market.OVERSEAS),
        StockSuggestion("GOOGL", "Alphabet Inc.", "Nasdaq", Market.OVERSEAS),
    )

    private lateinit var listLayout: LinearLayout
    private lateinit var suggestionsLayout: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var quantityInput: EditText
    private lateinit var purchasePriceInput: EditText
    private lateinit var summaryText: TextView
    private lateinit var portfolioTabButton: Button
    private lateinit var watchlistTabButton: Button
    private lateinit var domesticTabButton: Button
    private lateinit var overseasTabButton: Button
    private lateinit var topRefreshButton: Button
    private lateinit var addButton: Button
    private var syncReference: DatabaseReference? = null
    private lateinit var preferences: SharedPreferences
    private var pendingStockSearch: Runnable? = null
    private var autoRefreshRunnable: Runnable? = null
    private var stockSearchSequence = 0
    private var selectingSuggestion = false
    private var refreshInProgress = false
    private var screenVisible = false
    private var currentMarket = Market.DOMESTIC
    private var currentScreen = Screen.PORTFOLIO
    private var applyingRemoteSync = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadItems()
        loadWatchItems()
        setContentView(createContentView())
        renderList()
        setupCloudSync()
        refreshAllPrices(manual = false)
    }

    override fun onResume() {
        super.onResume()
        screenVisible = true
        startAutoRefresh()
    }

    override fun onPause() {
        screenVisible = false
        stopAutoRefresh()
        super.onPause()
    }

    override fun onDestroy() {
        stopAutoRefresh()
        executor.shutdownNow()
        searchExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(247, 249, 247))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(18), dp(20), dp(18), dp(24) + insets.systemWindowInsetBottom)
                insets
            }
        }
        scrollView.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header)

        val title = TextView(this).apply {
            text = "주식 목록"
            textSize = 28f
            setTextColor(Color.rgb(24, 35, 30))
            gravity = Gravity.START
            setTypeface(null, 1)
        }
        header.addView(title, weightParams(1f, 0))

        topRefreshButton = createButton("API 갱신", Color.rgb(235, 241, 238), Color.rgb(24, 35, 30)).apply {
            setOnClickListener { refreshAllPrices(manual = true) }
        }
        header.addView(topRefreshButton, LinearLayout.LayoutParams(dp(96), dp(42)))

        summaryText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(74, 86, 80))
            setPadding(0, dp(4), 0, dp(14))
        }
        root.addView(summaryText)

        val screenTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(screenTabs)

        portfolioTabButton = createButton("보유종목", Color.rgb(30, 122, 95), Color.WHITE).apply {
            setOnClickListener { switchScreen(Screen.PORTFOLIO) }
        }
        screenTabs.addView(portfolioTabButton, weightParams(1f, 0))

        watchlistTabButton = createButton("관심종목", Color.rgb(235, 241, 238), Color.rgb(24, 35, 30)).apply {
            setOnClickListener { switchScreen(Screen.WATCHLIST) }
        }
        screenTabs.addView(watchlistTabButton, weightParams(1f, dp(8)))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(form, matchWrapParams(0, dp(14)))

        val marketTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        form.addView(marketTabs)

        domesticTabButton = createButton("국내", Color.rgb(30, 122, 95), Color.WHITE).apply {
            setOnClickListener { switchMarket(Market.DOMESTIC) }
        }
        marketTabs.addView(domesticTabButton, weightParams(1f, 0))

        overseasTabButton = createButton("해외", Color.rgb(235, 241, 238), Color.rgb(24, 35, 30)).apply {
            setOnClickListener { switchMarket(Market.OVERSEAS) }
        }
        marketTabs.addView(overseasTabButton, weightParams(1f, dp(8)))

        nameInput = createInput("", InputType.TYPE_CLASS_TEXT)
        quantityInput = createInput("수량", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        purchasePriceInput = createInput("구매가(1주당)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        updateMarketTabUi()
        form.addView(nameInput, matchWrapParams(0, dp(10)))
        suggestionsLayout = createSuggestionsLayout()
        form.addView(suggestionsLayout, matchWrapParams(0, dp(4)))
        setupStockNameSearch()
        form.addView(quantityInput, matchWrapParams(0, dp(8)))
        form.addView(purchasePriceInput, matchWrapParams(0, dp(8)))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        form.addView(actions, matchWrapParams(0, dp(12)))

        addButton = createButton("+ 종목 추가", Color.rgb(30, 122, 95), Color.WHITE).apply {
            setOnClickListener { addItem() }
        }
        actions.addView(addButton, weightParams(1f, 0))

        val refreshButton = createButton("현재가 새로고침", Color.rgb(235, 241, 238), Color.rgb(24, 35, 30)).apply {
            setOnClickListener { refreshAllPrices(manual = true) }
        }
        actions.addView(refreshButton, weightParams(1f, dp(8)))

        listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listLayout)
        updateScreenUi()
        return scrollView
    }

    private fun switchScreen(screen: Screen) {
        if (currentScreen == screen) return
        currentScreen = screen
        selectingSuggestion = true
        nameInput.setText("")
        selectingSuggestion = false
        hideStockSuggestions()
        updateScreenUi()
        renderList()
    }

    private fun updateScreenUi() {
        if (!::portfolioTabButton.isInitialized) return
        styleTab(portfolioTabButton, currentScreen == Screen.PORTFOLIO)
        styleTab(watchlistTabButton, currentScreen == Screen.WATCHLIST)
        val portfolioMode = currentScreen == Screen.PORTFOLIO
        quantityInput.visibility = if (portfolioMode) View.VISIBLE else View.GONE
        purchasePriceInput.visibility = if (portfolioMode) View.VISIBLE else View.GONE
        addButton.text = if (portfolioMode) "+ 종목 추가" else "+ 관심 등록"
    }

    private fun switchMarket(market: Market) {
        if (currentMarket == market) return
        currentMarket = market
        selectingSuggestion = true
        nameInput.setText("")
        selectingSuggestion = false
        hideStockSuggestions()
        updateMarketTabUi()
        renderList()
    }

    private fun updateMarketTabUi() {
        styleTab(domesticTabButton, currentMarket == Market.DOMESTIC)
        styleTab(overseasTabButton, currentMarket == Market.OVERSEAS)
        if (::nameInput.isInitialized) {
            nameInput.hint = if (currentMarket == Market.DOMESTIC) {
                "국내 주식 이름 또는 종목코드 예: 삼성전자, 005930"
            } else {
                "해외 주식 이름 또는 티커 예: Apple, AAPL"
            }
        }
    }

    private fun styleTab(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.WHITE else Color.rgb(24, 35, 30))
        button.setBackgroundColor(if (selected) Color.rgb(30, 122, 95) else Color.rgb(235, 241, 238))
    }

    private fun createSuggestionsLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(250, 252, 250))
        visibility = View.GONE
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun setupStockNameSearch() {
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                if (!selectingSuggestion) {
                    scheduleStockSearch(editable?.toString()?.trim().orEmpty(), currentMarket)
                }
            }
        })
    }

    private fun scheduleStockSearch(query: String, market: Market) {
        pendingStockSearch?.let { mainHandler.removeCallbacks(it) }
        if (query.length < 2) {
            hideStockSuggestions()
            return
        }
        val requestId = ++stockSearchSequence
        val localSuggestions = findLocalStockSuggestions(query, market, MAX_SUGGESTIONS)
        if (localSuggestions.isEmpty()) showSuggestionMessage("검색 중...") else showStockSuggestions(localSuggestions)
        pendingStockSearch = Runnable { searchStockSuggestions(query, market, requestId) }
        mainHandler.postDelayed(pendingStockSearch!!, SEARCH_DELAY_MS)
    }

    private fun searchStockSuggestions(query: String, market: Market, requestId: Int) {
        searchExecutor.execute {
            try {
                val remote = if (market == Market.DOMESTIC) {
                    fetchDomesticStockSuggestions(query, MAX_SUGGESTIONS)
                } else {
                    fetchYahooStockSuggestions(query, MAX_SUGGESTIONS)
                }
                val suggestions = mergeSuggestions(
                    findLocalStockSuggestions(query, market, MAX_SUGGESTIONS),
                    remote,
                    MAX_SUGGESTIONS,
                )
                mainHandler.post {
                    if (requestId == stockSearchSequence && market == currentMarket) {
                        if (suggestions.isEmpty()) showSuggestionMessage("검색 결과 없음") else showStockSuggestions(suggestions)
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    if (requestId == stockSearchSequence && market == currentMarket) {
                        val local = findLocalStockSuggestions(query, market, MAX_SUGGESTIONS)
                        if (local.isEmpty()) showSuggestionMessage("검색 결과 없음") else showStockSuggestions(local)
                    }
                }
            }
        }
    }

    private fun showStockSuggestions(suggestions: List<StockSuggestion>) {
        suggestionsLayout.removeAllViews()
        if (suggestions.isEmpty()) {
            showSuggestionMessage("검색 결과 없음")
            return
        }
        suggestions.forEach { suggestionsLayout.addView(createSuggestionRow(it)) }
        suggestionsLayout.visibility = View.VISIBLE
    }

    private fun showSuggestionMessage(message: String) {
        suggestionsLayout.removeAllViews()
        suggestionsLayout.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.rgb(88, 100, 94))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        suggestionsLayout.visibility = View.VISIBLE
    }

    private fun createSuggestionRow(suggestion: StockSuggestion) = TextView(this).apply {
        text = suggestion.displayText()
        textSize = 14f
        setTextColor(Color.rgb(24, 35, 30))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener {
            selectingSuggestion = true
            currentMarket = suggestion.market
            updateMarketTabUi()
            nameInput.setText(suggestion.inputText())
            nameInput.setSelection(nameInput.text.length)
            selectingSuggestion = false
            hideStockSuggestions()
        }
    }

    private fun findLocalStockSuggestions(query: String, market: Market, limit: Int): List<StockSuggestion> {
        val normalizedQuery = normalizeSearchText(query)
        return commonStocks
            .asSequence()
            .filter { it.market == market && matchesStock(it, normalizedQuery) }
            .take(limit)
            .toList()
    }

    private fun matchesStock(stock: StockSuggestion, normalizedQuery: String): Boolean {
        return normalizeSearchText(stock.name).contains(normalizedQuery) ||
            normalizeSearchText(stock.symbol).contains(normalizedQuery)
    }

    private fun normalizeSearchText(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "")
            .replace("-", "")
            .replace("_", "")
            .replace("/", "")
    }

    private fun mergeSuggestions(first: List<StockSuggestion>, second: List<StockSuggestion>, limit: Int): List<StockSuggestion> {
        val merged = mutableListOf<StockSuggestion>()
        (first + second).forEach { suggestion ->
            if (merged.none { it.symbol.equals(suggestion.symbol, ignoreCase = true) }) {
                merged.add(suggestion)
                if (merged.size >= limit) return merged
            }
        }
        return merged
    }

    private fun hideStockSuggestions() {
        if (!::suggestionsLayout.isInitialized) return
        suggestionsLayout.removeAllViews()
        suggestionsLayout.visibility = View.GONE
    }

    private fun createInput(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        textSize = 15f
        inputType = type
        setPadding(dp(12), 0, dp(12), 0)
        setBackgroundColor(Color.rgb(244, 247, 245))
        minHeight = dp(48)
    }

    private fun createButton(textValue: String, backgroundColor: Int, textColor: Int) = Button(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(textColor)
        setBackgroundColor(backgroundColor)
        isAllCaps = false
        minHeight = dp(46)
    }

    private fun addItem() {
        if (currentScreen == Screen.WATCHLIST) {
            addWatchItem()
            return
        }
        val name = nameInput.text.toString().trim()
        val quantity = parseDouble(quantityInput.text.toString())
        val purchasePrice = parseDouble(purchasePriceInput.text.toString())
        if (name.isEmpty() || quantity == null || purchasePrice == null || quantity <= 0 || purchasePrice < 0) {
            Toast.makeText(this, "이름, 수량, 구매가를 올바르게 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val item = StockItem(name, quantity, purchasePrice, currentMarket)
        items.add(item)
        saveItems()
        clearInputs()
        renderList()
        refreshPrice(item)
    }

    private fun addWatchItem() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "관심종목 이름을 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (watchItems.any { it.market == currentMarket && it.name.equals(name, ignoreCase = true) }) {
            Toast.makeText(this, "이미 등록된 관심종목입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val item = WatchItem(name, currentMarket)
        watchItems.add(item)
        saveWatchItems()
        clearInputs()
        renderList()
        refreshPrice(item)
    }

    private fun clearInputs() {
        nameInput.setText("")
        quantityInput.setText("")
        purchasePriceInput.setText("")
        hideStockSuggestions()
    }

    private fun renderList() {
        listLayout.removeAllViews()
        updateSummary()
        if (currentScreen == Screen.WATCHLIST) {
            renderWatchList()
            return
        }
        val visibleItems = visibleItems()
        if (visibleItems.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = if (currentMarket == Market.DOMESTIC) "아직 추가한 국내 주식이 없습니다." else "아직 추가한 해외 주식이 없습니다."
                textSize = 16f
                setTextColor(Color.rgb(88, 100, 94))
                gravity = Gravity.CENTER
                setPadding(0, dp(36), 0, 0)
            })
            return
        }
        visibleItems.forEach { listLayout.addView(createStockRow(it), matchWrapParams(0, dp(10))) }
    }

    private fun renderWatchList() {
        val visibleItems = visibleWatchItems()
        if (visibleItems.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = if (currentMarket == Market.DOMESTIC) "아직 등록한 국내 관심종목이 없습니다." else "아직 등록한 해외 관심종목이 없습니다."
                textSize = 16f
                setTextColor(Color.rgb(88, 100, 94))
                gravity = Gravity.CENTER
                setPadding(0, dp(36), 0, 0)
            })
            return
        }
        visibleItems.forEach { listLayout.addView(createWatchRow(it), matchWrapParams(0, dp(10))) }
    }

    private fun visibleItems() = items.filter { it.market == currentMarket }
    private fun visibleWatchItems() = watchItems.filter { it.market == currentMarket }

    private fun createStockRow(item: StockItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(header)

        header.addView(TextView(this).apply {
            text = item.name
            textSize = 19f
            setTextColor(Color.rgb(24, 35, 30))
            setTypeface(null, 1)
        }, weightParams(1f, 0))

        val delete = createButton("삭제", Color.rgb(247, 230, 227), Color.rgb(150, 41, 31)).apply {
            minWidth = dp(72)
            setOnClickListener {
                items.remove(item)
                saveItems()
                renderList()
            }
        }
        header.addView(delete, LinearLayout.LayoutParams(dp(76), dp(42)))

        card.addView(createInfoLine("구분", if (item.market == Market.DOMESTIC) "국내" else "해외"), matchWrapParams(0, dp(10)))
        card.addView(createInfoLine("수량", "${amountFormat.format(item.quantity)}주"))
        card.addView(createInfoLine("구매가", formatMoney(item.purchasePrice, item.market)))
        card.addView(createInfoLine("구매금액", formatMoney(item.purchaseAmount(), item.market)))
        card.addView(createInfoLine("현재가", item.currentPrice?.let { formatMoney(it, item.market) } ?: "불러오는 중"))

        val currentValue = item.currentPrice?.let { it * item.quantity } ?: 0.0
        val profitAmount = currentValue - item.purchaseAmount()
        val profit = createInfoLine(
            "평가금액 / 손익",
            item.currentPrice?.let { "${formatMoney(currentValue, item.market)} / ${signedMoney(profitAmount, item.market)}" } ?: "-",
        )
        if (item.currentPrice != null) {
            profit.setTextColor(if (profitAmount >= 0) Color.rgb(204, 47, 42) else Color.rgb(38, 95, 181))
        }
        card.addView(profit)

        item.errorMessage?.let {
            card.addView(TextView(this).apply {
                text = it
                textSize = 13f
                setTextColor(Color.rgb(164, 86, 40))
                setPadding(0, dp(8), 0, 0)
            })
        }
        return card
    }

    private fun createWatchRow(item: WatchItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(header)

        header.addView(TextView(this).apply {
            text = item.name
            textSize = 19f
            setTextColor(Color.rgb(24, 35, 30))
            setTypeface(null, 1)
        }, weightParams(1f, 0))

        val delete = createButton("삭제", Color.rgb(247, 230, 227), Color.rgb(150, 41, 31)).apply {
            minWidth = dp(72)
            setOnClickListener {
                watchItems.remove(item)
                saveWatchItems()
                renderList()
            }
        }
        header.addView(delete, LinearLayout.LayoutParams(dp(76), dp(42)))

        card.addView(createInfoLine("구분", if (item.market == Market.DOMESTIC) "국내" else "해외"), matchWrapParams(0, dp(10)))
        card.addView(createInfoLine("현재가", item.currentPrice?.let { formatMoney(it, item.market) } ?: "불러오는 중"))

        item.errorMessage?.let {
            card.addView(TextView(this).apply {
                text = it
                textSize = 13f
                setTextColor(Color.rgb(164, 86, 40))
                setPadding(0, dp(8), 0, 0)
            })
        }
        return card
    }

    private fun createInfoLine(label: String, value: String) = TextView(this).apply {
        text = "$label: $value"
        textSize = 15f
        setTextColor(Color.rgb(65, 76, 70))
        setPadding(0, dp(4), 0, 0)
    }

    private fun updateSummary() {
        if (currentScreen == Screen.WATCHLIST) {
            val visibleItems = visibleWatchItems()
            summaryText.text = "${if (currentMarket == Market.DOMESTIC) "국내" else "해외"} 관심종목 ${visibleItems.size}개"
            return
        }
        val visibleItems = visibleItems()
        val invested = visibleItems.sumOf { it.purchaseAmount() }
        val currentValue = visibleItems.sumOf { (it.currentPrice ?: 0.0) * it.quantity }
        summaryText.text = "${if (currentMarket == Market.DOMESTIC) "국내" else "해외"} ${visibleItems.size}개 | 구매금액 ${formatMoney(invested, currentMarket)} | 현재 평가 ${formatMoney(currentValue, currentMarket)}"
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshRunnable = object : Runnable {
            override fun run() {
                if (screenVisible) {
                    refreshAllPrices(manual = false)
                    mainHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(autoRefreshRunnable!!, AUTO_REFRESH_INTERVAL_MS)
    }

    private fun stopAutoRefresh() {
        autoRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        autoRefreshRunnable = null
    }

    private fun refreshAllPrices(manual: Boolean) {
        if (items.isEmpty() && watchItems.isEmpty()) {
            updateSummary()
            return
        }
        if (refreshInProgress) {
            if (manual) Toast.makeText(this, "이미 현재가를 갱신하는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        refreshInProgress = true
        updateRefreshButtonState()
        if (manual) Toast.makeText(this, "현재가를 불러오는 중입니다.", Toast.LENGTH_SHORT).show()
        val refreshTargets = items.toList()
        val watchRefreshTargets = watchItems.toList()
        executor.execute {
            refreshTargets.forEach { item ->
                item.errorMessage = null
                try {
                    item.currentPrice = fetchCurrentPrice(item)
                    item.errorMessage = if (item.currentPrice == null) currentPriceErrorMessage(item.market) else null
                } catch (_: Exception) {
                    item.errorMessage = "현재가 API 호출에 실패했습니다."
                }
            }
            watchRefreshTargets.forEach { item ->
                item.errorMessage = null
                try {
                    item.currentPrice = fetchCurrentPrice(item.name, item.market)
                    item.errorMessage = if (item.currentPrice == null) currentPriceErrorMessage(item.market) else null
                } catch (_: Exception) {
                    item.errorMessage = "현재가 API 호출에 실패했습니다."
                }
            }
            mainHandler.post {
                refreshInProgress = false
                updateRefreshButtonState()
                saveItems()
                saveWatchItems()
                renderList()
            }
        }
    }

    private fun updateRefreshButtonState() {
        if (::topRefreshButton.isInitialized) {
            topRefreshButton.isEnabled = !refreshInProgress
            topRefreshButton.text = if (refreshInProgress) "갱신 중" else "API 갱신"
        }
    }

    private fun setupCloudSync() {
        try {
            try {
                FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            } catch (_: Exception) {
            }
            syncReference = FirebaseDatabase.getInstance()
                .reference
                .child("stockPortfolio")
                .child("sharedLists")
                .child("default")
            syncReference?.keepSynced(true)
            syncReference?.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val portfolioJson = snapshot.child("portfolioJson").getValue(String::class.java)
                    val watchJson = snapshot.child("watchJson").getValue(String::class.java)
                    if (portfolioJson == null && watchJson == null) {
                        pushAllListsToCloud()
                        return
                    }

                    applyingRemoteSync = true
                    portfolioJson?.let {
                        applyItemsJson(it)
                        preferences.edit().putString(KEY_ITEMS, it).apply()
                    }
                    watchJson?.let {
                        applyWatchItemsJson(it)
                        preferences.edit().putString(KEY_WATCH_ITEMS, it).apply()
                    }
                    applyingRemoteSync = false
                    renderList()
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@MainActivity, "목록 동기화에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            })
        } catch (_: Exception) {
            Toast.makeText(this, "클라우드 동기화를 시작하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pushAllListsToCloud() {
        syncReference?.updateChildren(
            mapOf(
                "portfolioJson" to serializeItems(),
                "watchJson" to serializeWatchItems(),
            ),
        )
    }

    private fun pushPortfolioToCloud() {
        if (!applyingRemoteSync) {
            syncReference?.child("portfolioJson")?.setValue(serializeItems())
        }
    }

    private fun pushWatchListToCloud() {
        if (!applyingRemoteSync) {
            syncReference?.child("watchJson")?.setValue(serializeWatchItems())
        }
    }

    private fun refreshPrice(item: StockItem) {
        executor.execute {
            try {
                val price = fetchCurrentPrice(item)
                mainHandler.post {
                    item.currentPrice = price
                    item.errorMessage = if (price == null) currentPriceErrorMessage(item.market) else null
                    saveItems()
                    renderList()
                }
            } catch (_: Exception) {
                mainHandler.post {
                    item.errorMessage = "현재가 API 호출에 실패했습니다."
                    renderList()
                }
            }
        }
    }

    private fun refreshPrice(item: WatchItem) {
        executor.execute {
            try {
                val price = fetchCurrentPrice(item.name, item.market)
                mainHandler.post {
                    item.currentPrice = price
                    item.errorMessage = if (price == null) currentPriceErrorMessage(item.market) else null
                    saveWatchItems()
                    renderList()
                }
            } catch (_: Exception) {
                mainHandler.post {
                    item.errorMessage = "현재가 API 호출에 실패했습니다."
                    renderList()
                }
            }
        }
    }

    private fun currentPriceErrorMessage(market: Market) = if (market == Market.DOMESTIC) {
        "국내 현재가를 찾지 못했습니다. 예: 삼성전자, 005930처럼 입력해보세요."
    } else {
        "해외 현재가를 찾지 못했습니다. 예: AAPL처럼 입력해보세요."
    }

    private fun fetchCurrentPrice(item: StockItem): Double? {
        return fetchCurrentPrice(item.name, item.market)
    }

    private fun fetchCurrentPrice(name: String, market: Market): Double? {
        return if (market == Market.DOMESTIC) {
            fetchDomesticCurrentPrice(name)
        } else {
            fetchOverseasCurrentPrice(name)
        }
    }

    private fun fetchDomesticCurrentPrice(stockName: String): Double? {
        var code = normalizeDomesticCode(extractSymbol(stockName))
        if (!isDomesticCode(code)) code = searchDomesticSymbol(code).orEmpty()
        if (!isDomesticCode(code)) return null

        val urls = arrayOf(
            "https://m.stock.naver.com/api/stock/$code/basic",
            "https://api.stock.naver.com/stock/$code/basic",
        )
        var lastException: Exception? = null
        urls.forEach { urlText ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(urlText).openConnection() as HttpURLConnection
                configureFinanceConnection(connection)
                BufferedReader(InputStreamReader(connection.inputStream)).use {
                    parseDomesticPrice(JSONObject(readAll(it)))?.let { price -> return price }
                }
            } catch (e: Exception) {
                lastException = e
            } finally {
                connection?.disconnect()
            }
        }
        lastException?.let { throw it }
        return null
    }

    private fun fetchOverseasCurrentPrice(stockName: String): Double? {
        val symbol = extractSymbol(stockName)
        try {
            fetchYahooPriceForSymbol(symbol)?.let { return it }
        } catch (_: Exception) {
        }
        val resolvedSymbol = try {
            searchYahooSymbol(symbol)
        } catch (_: Exception) {
            null
        }
        return resolvedSymbol?.takeIf { it.isNotBlank() }?.let { fetchYahooPriceForSymbol(it) }
    }

    private fun fetchYahooPriceForSymbol(symbol: String): Double? {
        val encodedSymbol = URLEncoder.encode(symbol, "UTF-8")
        val connection = URL("https://query1.finance.yahoo.com/v8/finance/chart/$encodedSymbol?range=1d&interval=1d")
            .openConnection() as HttpURLConnection
        configureFinanceConnection(connection)
        try {
            BufferedReader(InputStreamReader(connection.inputStream)).use {
                val meta = JSONObject(readAll(it))
                    .getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0)
                    .getJSONObject("meta")
                val price = if (meta.has("regularMarketPrice")) meta.getDouble("regularMarketPrice") else meta.optDouble("previousClose", Double.NaN)
                return if (price.isNaN()) null else price
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun searchYahooSymbol(query: String): String? = fetchYahooStockSuggestions(query, 1).firstOrNull()?.symbol

    private fun searchDomesticSymbol(query: String): String? {
        findLocalStockSuggestions(query, Market.DOMESTIC, 1).firstOrNull()?.let { return it.symbol }
        return fetchDomesticStockSuggestions(query, 1).firstOrNull()?.symbol
    }

    private fun fetchYahooStockSuggestions(query: String, limit: Int): List<StockSuggestion> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val connection = URL("https://query2.finance.yahoo.com/v1/finance/search?q=$encodedQuery&quotesCount=$limit&newsCount=0")
            .openConnection() as HttpURLConnection
        configureFinanceConnection(connection)
        try {
            BufferedReader(InputStreamReader(connection.inputStream)).use {
                val quotes = JSONObject(readAll(it)).optJSONArray("quotes") ?: return emptyList()
                val suggestions = mutableListOf<StockSuggestion>()
                for (i in 0 until quotes.length()) {
                    if (suggestions.size >= limit) break
                    val quote = quotes.getJSONObject(i)
                    val symbol = quote.optString("symbol", "").trim()
                    val name = quote.optString("shortname", quote.optString("longname", "")).trim()
                    val exchange = quote.optString("exchDisp", "").trim()
                    if (symbol.isNotEmpty()) {
                        suggestions.add(StockSuggestion(symbol, name.ifEmpty { symbol }, exchange, Market.OVERSEAS))
                    }
                }
                return suggestions
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchDomesticStockSuggestions(query: String, limit: Int): List<StockSuggestion> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val connection = URL("https://ac.finance.naver.com/ac?q=$encodedQuery&q_enc=UTF-8&st=111&r_lt=111&r_format=json")
            .openConnection() as HttpURLConnection
        configureFinanceConnection(connection)
        try {
            BufferedReader(InputStreamReader(connection.inputStream)).use {
                val suggestions = mutableListOf<StockSuggestion>()
                collectDomesticSuggestions(readAll(it).trim(), suggestions, limit)
                return suggestions
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun collectDomesticSuggestions(body: String, suggestions: MutableList<StockSuggestion>, limit: Int) {
        if (body.isEmpty()) return
        val json = trimJson(body)
        if (json.startsWith("{")) {
            collectDomesticSuggestionsFromObject(JSONObject(json), suggestions, limit)
        } else if (json.startsWith("[")) {
            collectDomesticSuggestionsFromArray(JSONArray(json), suggestions, limit)
        }
    }

    private fun collectDomesticSuggestionsFromObject(obj: JSONObject, suggestions: MutableList<StockSuggestion>, limit: Int) {
        if (suggestions.size >= limit) return
        val code = firstNonEmpty(obj.optString("code", ""), obj.optString("symbol", ""), obj.optString("itemCode", ""), obj.optString("stockCode", ""))
        val name = firstNonEmpty(obj.optString("name", ""), obj.optString("korName", ""), obj.optString("stockName", ""), obj.optString("itemName", ""))
        val market = firstNonEmpty(obj.optString("market", ""), obj.optString("marketName", ""), obj.optString("exchange", ""))
        addDomesticSuggestion(suggestions, code, name, market, limit)

        val names = obj.names() ?: return
        for (i in 0 until names.length()) {
            if (suggestions.size >= limit) return
            when (val value = obj.opt(names.getString(i))) {
                is JSONObject -> collectDomesticSuggestionsFromObject(value, suggestions, limit)
                is JSONArray -> collectDomesticSuggestionsFromArray(value, suggestions, limit)
            }
        }
    }

    private fun collectDomesticSuggestionsFromArray(array: JSONArray, suggestions: MutableList<StockSuggestion>, limit: Int) {
        if (suggestions.size >= limit) return
        var code = ""
        var name = ""
        var market = ""
        for (i in 0 until array.length()) {
            if (suggestions.size >= limit) return
            when (val value = array.opt(i)) {
                is JSONObject -> collectDomesticSuggestionsFromObject(value, suggestions, limit)
                is JSONArray -> {
                    addDomesticSuggestionFromFlatArray(value, suggestions, limit)
                    collectDomesticSuggestionsFromArray(value, suggestions, limit)
                }
                is String -> {
                    val text = value.trim()
                    when {
                        isDomesticCode(text) -> code = text
                        name.isEmpty() && text.isNotEmpty() -> name = text
                        market.isEmpty() && text.isNotEmpty() -> market = text
                    }
                }
            }
        }
        addDomesticSuggestion(suggestions, code, name, market, limit)
    }

    private fun addDomesticSuggestionFromFlatArray(array: JSONArray, suggestions: MutableList<StockSuggestion>, limit: Int) {
        var code = ""
        var name = ""
        var market = ""
        for (i in 0 until array.length()) {
            val text = array.optString(i, "").trim()
            when {
                isDomesticCode(text) -> code = text
                name.isEmpty() && looksLikeStockName(text) -> name = text
                market.isEmpty() && (text.contains("KOSPI") || text.contains("KOSDAQ") || text.contains("코스")) -> market = text
            }
        }
        addDomesticSuggestion(suggestions, code, name, market, limit)
    }

    private fun addDomesticSuggestion(suggestions: MutableList<StockSuggestion>, rawCode: String?, rawName: String?, rawMarket: String?, limit: Int) {
        val code = normalizeDomesticCode(rawCode)
        val name = rawName?.trim().orEmpty()
        if (!isDomesticCode(code) || name.isEmpty() || suggestions.size >= limit) return
        if (suggestions.none { it.symbol.equals(code, ignoreCase = true) }) {
            suggestions.add(StockSuggestion(code, name, rawMarket?.trim().orEmpty(), Market.DOMESTIC))
        }
    }

    private fun looksLikeStockName(text: String): Boolean {
        return text.isNotEmpty() && !text.startsWith("http") && !text.contains("<") && !isDomesticCode(text)
    }

    private fun trimJson(body: String): String {
        val firstObject = body.indexOf('{')
        val firstArray = body.indexOf('[')
        val start = when {
            firstObject < 0 -> firstArray
            firstArray < 0 -> firstObject
            else -> minOf(firstObject, firstArray)
        }
        val end = maxOf(body.lastIndexOf('}'), body.lastIndexOf(']'))
        return if (start >= 0 && end >= start) body.substring(start, end + 1) else body
    }

    private fun configureFinanceConnection(connection: HttpURLConnection) {
        connection.requestMethod = "GET"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Referer", "https://m.stock.naver.com/")
    }

    private fun readAll(reader: BufferedReader): String = buildString {
        var line = reader.readLine()
        while (line != null) {
            append(line)
            line = reader.readLine()
        }
    }

    private fun extractSymbol(input: String): String {
        val open = input.lastIndexOf('(')
        val close = input.lastIndexOf(')')
        return if (open >= 0 && close > open) input.substring(open + 1, close).trim() else input
    }

    private fun normalizeDomesticCode(raw: String?): String {
        var code = raw?.trim().orEmpty()
        if (code.endsWith(".KS") || code.endsWith(".KQ")) {
            code = code.dropLast(3)
        }
        return code.uppercase(Locale.ROOT)
    }

    private fun isDomesticCode(code: String?): Boolean = code?.uppercase(Locale.ROOT)?.matches(Regex("[0-9A-Z]{6}")) == true

    private fun firstNonEmpty(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun parsePrice(raw: String?): Double? = raw?.takeIf { it.isNotBlank() }?.replace(",", "")?.trim()?.toDoubleOrNull()

    private fun parseDomesticPrice(root: JSONObject): Double? {
        val keys = arrayOf("closePrice", "stockEndPrice", "nowVal", "currentPrice", "price")
        return keys.firstNotNullOfOrNull { key -> parsePrice(root.optString(key, "")) }
    }

    private fun loadItems() {
        items.clear()
        val raw = preferences.getString(KEY_ITEMS, "[]") ?: "[]"
        applyItemsJson(raw)
    }

    private fun applyItemsJson(raw: String) {
        items.clear()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val item = StockItem(
                    name,
                    obj.getDouble("quantity"),
                    if (obj.has("purchasePrice")) obj.getDouble("purchasePrice") else obj.getDouble("totalPrice"),
                    parseMarket(obj.optString("market", ""), name),
                )
                if (obj.has("currentPrice") && !obj.isNull("currentPrice")) {
                    item.currentPrice = obj.getDouble("currentPrice")
                }
                items.add(item)
            }
        } catch (_: Exception) {
            items.clear()
        }
    }

    private fun parseMarket(raw: String, name: String): Market {
        return when (raw) {
            "DOMESTIC" -> Market.DOMESTIC
            "OVERSEAS" -> Market.OVERSEAS
            else -> if (isDomesticCode(normalizeDomesticCode(extractSymbol(name)))) Market.DOMESTIC else Market.OVERSEAS
        }
    }

    private fun saveItems() {
        val raw = serializeItems()
        preferences.edit().putString(KEY_ITEMS, raw).apply()
        pushPortfolioToCloud()
    }

    private fun serializeItems(): String {
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
            try {
                obj.put("name", item.name)
                obj.put("quantity", item.quantity)
                obj.put("purchasePrice", item.purchasePrice)
                obj.put("totalPrice", item.purchaseAmount())
                obj.put("market", item.market.name)
                obj.put("currentPrice", item.currentPrice ?: JSONObject.NULL)
                array.put(obj)
            } catch (_: Exception) {
            }
        }
        return array.toString()
    }

    private fun loadWatchItems() {
        watchItems.clear()
        val raw = preferences.getString(KEY_WATCH_ITEMS, "[]") ?: "[]"
        applyWatchItemsJson(raw)
    }

    private fun applyWatchItemsJson(raw: String) {
        watchItems.clear()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val item = WatchItem(
                    name = name,
                    market = parseMarket(obj.optString("market", ""), name),
                )
                if (obj.has("currentPrice") && !obj.isNull("currentPrice")) {
                    item.currentPrice = obj.getDouble("currentPrice")
                }
                watchItems.add(item)
            }
        } catch (_: Exception) {
            watchItems.clear()
        }
    }

    private fun saveWatchItems() {
        val raw = serializeWatchItems()
        preferences.edit().putString(KEY_WATCH_ITEMS, raw).apply()
        pushWatchListToCloud()
    }

    private fun serializeWatchItems(): String {
        val array = JSONArray()
        watchItems.forEach { item ->
            val obj = JSONObject()
            try {
                obj.put("name", item.name)
                obj.put("market", item.market.name)
                obj.put("currentPrice", item.currentPrice ?: JSONObject.NULL)
                array.put(obj)
            } catch (_: Exception) {
            }
        }
        return array.toString()
    }

    private fun parseDouble(raw: String): Double? = raw.replace(",", "").trim().toDoubleOrNull()

    private fun formatMoney(value: Double): String = formatMoney(value, Market.DOMESTIC)

    private fun formatMoney(value: Double, market: Market): String {
        return if (market == Market.OVERSEAS) "$${amountFormat.format(value)}" else "${amountFormat.format(value)}원"
    }

    private fun signedMoney(value: Double): String = signedMoney(value, Market.DOMESTIC)

    private fun signedMoney(value: Double, market: Market): String {
        return when {
            value > 0 -> "+${formatMoney(value, market)}"
            value < 0 -> "-${formatMoney(abs(value), market)}"
            else -> formatMoney(value, market)
        }
    }

    private fun matchWrapParams(left: Int, top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(left, top, 0, 0) }

    private fun weightParams(weight: Float, leftMargin: Int) = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        weight,
    ).apply { setMargins(leftMargin, 0, 0, 0) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
        var currentPrice: Double? = null,
        var errorMessage: String? = null,
    ) {
        fun purchaseAmount(): Double = purchasePrice * quantity
    }

    private data class WatchItem(
        val name: String,
        val market: Market,
        var currentPrice: Double? = null,
        var errorMessage: String? = null,
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
}
