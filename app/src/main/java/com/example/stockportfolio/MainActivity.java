package com.example.stockportfolio;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "stock_portfolio";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_SUGGESTIONS = 5;
    private static final long SEARCH_DELAY_MS = 350;
    private static final long AUTO_REFRESH_INTERVAL_MS = 60000;

    private final List<StockItem> items = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DecimalFormat amountFormat = new DecimalFormat("#,##0.##");
    private final StockSuggestion[] commonStocks = new StockSuggestion[]{
            new StockSuggestion("005930", "삼성전자", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("009150", "삼성전기", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("000660", "SK하이닉스", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("373220", "LG에너지솔루션", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("005380", "현대차", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("000270", "기아", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("035420", "NAVER", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("035720", "카카오", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("207940", "삼성바이오로직스", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("051910", "LG화학", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("006400", "삼성SDI", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("068270", "셀트리온", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("005490", "POSCO홀딩스", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("105560", "KB금융", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("055550", "신한지주", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("012330", "현대모비스", "KOSPI", Market.DOMESTIC),
            new StockSuggestion("144600", "KODEX 은선물(H)", "KOSPI ETF", Market.DOMESTIC),
            new StockSuggestion("0172V0", "1Q 은액티브", "KOSPI ETF", Market.DOMESTIC),
            new StockSuggestion("AAPL", "Apple Inc.", "Nasdaq", Market.OVERSEAS),
            new StockSuggestion("MSFT", "Microsoft Corporation", "Nasdaq", Market.OVERSEAS),
            new StockSuggestion("TSLA", "Tesla, Inc.", "Nasdaq", Market.OVERSEAS),
            new StockSuggestion("NVDA", "NVIDIA Corporation", "Nasdaq", Market.OVERSEAS),
            new StockSuggestion("GOOGL", "Alphabet Inc.", "Nasdaq", Market.OVERSEAS)
    };

    private LinearLayout listLayout;
    private LinearLayout suggestionsLayout;
    private EditText nameInput;
    private EditText quantityInput;
    private EditText purchasePriceInput;
    private TextView summaryText;
    private Button domesticTabButton;
    private Button overseasTabButton;
    private Button topRefreshButton;
    private SharedPreferences preferences;
    private Runnable pendingStockSearch;
    private Runnable autoRefreshRunnable;
    private int stockSearchSequence = 0;
    private boolean selectingSuggestion = false;
    private boolean refreshInProgress = false;
    private boolean screenVisible = false;
    private Market currentMarket = Market.DOMESTIC;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadItems();
        setContentView(createContentView());
        renderList();
        refreshAllPrices(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        screenVisible = true;
        startAutoRefresh();
    }

    @Override
    protected void onPause() {
        screenVisible = false;
        stopAutoRefresh();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAutoRefresh();
        executor.shutdownNow();
        searchExecutor.shutdownNow();
        super.onDestroy();
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(247, 249, 247));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(18), dp(20), dp(18), dp(24) + bottomInset);
            return insets;
        });
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header);

        TextView title = new TextView(this);
        title.setText("주식 목록");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(24, 35, 30));
        title.setGravity(Gravity.START);
        title.setTypeface(null, 1);
        header.addView(title, weightParams(1, 0));

        topRefreshButton = createButton("API 갱신", Color.rgb(235, 241, 238), Color.rgb(24, 35, 30));
        topRefreshButton.setOnClickListener(v -> refreshAllPrices(true));
        header.addView(topRefreshButton, new LinearLayout.LayoutParams(dp(96), dp(42)));

        summaryText = new TextView(this);
        summaryText.setTextSize(15);
        summaryText.setTextColor(Color.rgb(74, 86, 80));
        summaryText.setPadding(0, dp(4), 0, dp(14));
        root.addView(summaryText);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackgroundColor(Color.WHITE);
        form.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(form, matchWrapParams(0, dp(14)));

        LinearLayout marketTabs = new LinearLayout(this);
        marketTabs.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(marketTabs);

        domesticTabButton = createButton("국내", Color.rgb(30, 122, 95), Color.WHITE);
        domesticTabButton.setOnClickListener(v -> switchMarket(Market.DOMESTIC));
        marketTabs.addView(domesticTabButton, weightParams(1, 0));

        overseasTabButton = createButton("해외", Color.rgb(235, 241, 238), Color.rgb(24, 35, 30));
        overseasTabButton.setOnClickListener(v -> switchMarket(Market.OVERSEAS));
        marketTabs.addView(overseasTabButton, weightParams(1, dp(8)));

        nameInput = createInput("", InputType.TYPE_CLASS_TEXT);
        quantityInput = createInput("수량", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        purchasePriceInput = createInput("구매가(1주당)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        updateMarketTabUi();
        form.addView(nameInput, matchWrapParams(0, dp(10)));
        suggestionsLayout = createSuggestionsLayout();
        form.addView(suggestionsLayout, matchWrapParams(0, dp(4)));
        setupStockNameSearch();
        form.addView(quantityInput, matchWrapParams(0, dp(8)));
        form.addView(purchasePriceInput, matchWrapParams(0, dp(8)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(actions, matchWrapParams(0, dp(12)));

        Button addButton = createButton("+ 종목 추가", Color.rgb(30, 122, 95), Color.WHITE);
        addButton.setOnClickListener(v -> addItem());
        actions.addView(addButton, weightParams(1, 0));

        Button refreshButton = createButton("현재가 새로고침", Color.rgb(235, 241, 238), Color.rgb(24, 35, 30));
        refreshButton.setOnClickListener(v -> refreshAllPrices(true));
        actions.addView(refreshButton, weightParams(1, dp(8)));

        listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(listLayout);
        return scrollView;
    }

    private void switchMarket(Market market) {
        if (currentMarket == market) {
            return;
        }
        currentMarket = market;
        selectingSuggestion = true;
        nameInput.setText("");
        selectingSuggestion = false;
        hideStockSuggestions();
        updateMarketTabUi();
    }

    private void updateMarketTabUi() {
        styleTab(domesticTabButton, currentMarket == Market.DOMESTIC);
        styleTab(overseasTabButton, currentMarket == Market.OVERSEAS);
        if (nameInput != null) {
            nameInput.setHint(currentMarket == Market.DOMESTIC
                    ? "국내 주식 이름 또는 종목코드 예: 삼성전자, 005930"
                    : "해외 주식 이름 또는 티커 예: Apple, AAPL");
        }
    }

    private void styleTab(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setTextColor(selected ? Color.WHITE : Color.rgb(24, 35, 30));
        button.setBackgroundColor(selected ? Color.rgb(30, 122, 95) : Color.rgb(235, 241, 238));
    }

    private LinearLayout createSuggestionsLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(250, 252, 250));
        layout.setVisibility(View.GONE);
        layout.setPadding(0, dp(4), 0, dp(4));
        return layout;
    }

    private void setupStockNameSearch() {
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!selectingSuggestion) {
                    scheduleStockSearch(editable.toString().trim(), currentMarket);
                }
            }
        });
    }

    private void scheduleStockSearch(String query, Market market) {
        if (pendingStockSearch != null) {
            mainHandler.removeCallbacks(pendingStockSearch);
        }
        if (query.length() < 2) {
            hideStockSuggestions();
            return;
        }

        int requestId = ++stockSearchSequence;
        List<StockSuggestion> localSuggestions = findLocalStockSuggestions(query, market, MAX_SUGGESTIONS);
        if (localSuggestions.isEmpty()) {
            showSuggestionMessage("검색 중...");
        } else {
            showStockSuggestions(localSuggestions);
        }
        pendingStockSearch = () -> searchStockSuggestions(query, market, requestId);
        mainHandler.postDelayed(pendingStockSearch, SEARCH_DELAY_MS);
    }

    private void searchStockSuggestions(String query, Market market, int requestId) {
        searchExecutor.execute(() -> {
            try {
                List<StockSuggestion> suggestions = mergeSuggestions(
                        findLocalStockSuggestions(query, market, MAX_SUGGESTIONS),
                        market == Market.DOMESTIC
                                ? fetchDomesticStockSuggestions(query, MAX_SUGGESTIONS)
                                : fetchYahooStockSuggestions(query, MAX_SUGGESTIONS),
                        MAX_SUGGESTIONS
                );
                mainHandler.post(() -> {
                    if (requestId == stockSearchSequence && market == currentMarket) {
                        if (suggestions.isEmpty()) {
                            showSuggestionMessage("검색 결과 없음");
                        } else {
                            showStockSuggestions(suggestions);
                        }
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (requestId == stockSearchSequence && market == currentMarket) {
                        List<StockSuggestion> localSuggestions = findLocalStockSuggestions(query, market, MAX_SUGGESTIONS);
                        if (localSuggestions.isEmpty()) {
                            showSuggestionMessage("검색 결과 없음");
                        } else {
                            showStockSuggestions(localSuggestions);
                        }
                    }
                });
            }
        });
    }

    private void showStockSuggestions(List<StockSuggestion> suggestions) {
        suggestionsLayout.removeAllViews();
        if (suggestions.isEmpty()) {
            showSuggestionMessage("검색 결과 없음");
            return;
        }

        for (StockSuggestion suggestion : suggestions) {
            suggestionsLayout.addView(createSuggestionRow(suggestion));
        }
        suggestionsLayout.setVisibility(View.VISIBLE);
    }

    private void showSuggestionMessage(String message) {
        suggestionsLayout.removeAllViews();
        TextView row = new TextView(this);
        row.setText(message);
        row.setTextSize(14);
        row.setTextColor(Color.rgb(88, 100, 94));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        suggestionsLayout.addView(row);
        suggestionsLayout.setVisibility(View.VISIBLE);
    }

    private TextView createSuggestionRow(StockSuggestion suggestion) {
        TextView row = new TextView(this);
        row.setText(suggestion.displayText());
        row.setTextSize(14);
        row.setTextColor(Color.rgb(24, 35, 30));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setOnClickListener(v -> {
            selectingSuggestion = true;
            currentMarket = suggestion.market;
            updateMarketTabUi();
            nameInput.setText(suggestion.inputText());
            nameInput.setSelection(nameInput.getText().length());
            selectingSuggestion = false;
            hideStockSuggestions();
        });
        return row;
    }

    private List<StockSuggestion> findLocalStockSuggestions(String query, Market market, int limit) {
        String normalizedQuery = normalizeSearchText(query);
        List<StockSuggestion> suggestions = new ArrayList<>();
        for (StockSuggestion stock : commonStocks) {
            if (stock.market == market && matchesStock(stock, normalizedQuery)) {
                suggestions.add(stock);
                if (suggestions.size() >= limit) {
                    break;
                }
            }
        }
        return suggestions;
    }

    private boolean matchesStock(StockSuggestion stock, String normalizedQuery) {
        return normalizeSearchText(stock.name).contains(normalizedQuery)
                || normalizeSearchText(stock.symbol).contains(normalizedQuery);
    }

    private String normalizeSearchText(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("(", "")
                .replace(")", "")
                .replace("-", "")
                .replace("_", "")
                .replace("/", "");
    }

    private List<StockSuggestion> mergeSuggestions(List<StockSuggestion> first,
                                                   List<StockSuggestion> second,
                                                   int limit) {
        List<StockSuggestion> merged = new ArrayList<>();
        for (StockSuggestion suggestion : first) {
            if (!containsSymbol(merged, suggestion.symbol)) {
                merged.add(suggestion);
                if (merged.size() >= limit) {
                    return merged;
                }
            }
        }
        for (StockSuggestion suggestion : second) {
            if (!containsSymbol(merged, suggestion.symbol)) {
                merged.add(suggestion);
                if (merged.size() >= limit) {
                    break;
                }
            }
        }
        return merged;
    }

    private boolean containsSymbol(List<StockSuggestion> suggestions, String symbol) {
        for (StockSuggestion suggestion : suggestions) {
            if (suggestion.symbol.equalsIgnoreCase(symbol)) {
                return true;
            }
        }
        return false;
    }

    private void hideStockSuggestions() {
        if (suggestionsLayout == null) {
            return;
        }
        suggestionsLayout.removeAllViews();
        suggestionsLayout.setVisibility(View.GONE);
    }

    private EditText createInput(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setInputType(inputType);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackgroundColor(Color.rgb(244, 247, 245));
        input.setMinHeight(dp(48));
        return input;
    }

    private Button createButton(String text, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(textColor);
        button.setBackgroundColor(backgroundColor);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        return button;
    }

    private void addItem() {
        String name = nameInput.getText().toString().trim();
        Double quantity = parseDouble(quantityInput.getText().toString());
        Double purchasePrice = parseDouble(purchasePriceInput.getText().toString());

        if (name.isEmpty() || quantity == null || purchasePrice == null || quantity <= 0 || purchasePrice < 0) {
            Toast.makeText(this, "이름, 수량, 구매가를 올바르게 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        StockItem item = new StockItem(name, quantity, purchasePrice, currentMarket);
        items.add(item);
        saveItems();
        clearInputs();
        renderList();
        refreshPrice(item);
    }

    private void clearInputs() {
        nameInput.setText("");
        quantityInput.setText("");
        purchasePriceInput.setText("");
        hideStockSuggestions();
    }

    private void renderList() {
        listLayout.removeAllViews();
        updateSummary();

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("아직 추가한 주식이 없습니다.");
            empty.setTextSize(16);
            empty.setTextColor(Color.rgb(88, 100, 94));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(36), 0, 0);
            listLayout.addView(empty);
            return;
        }

        for (StockItem item : items) {
            listLayout.addView(createStockRow(item), matchWrapParams(0, dp(10)));
        }
    }

    private View createStockRow(StockItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);

        TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextSize(19);
        name.setTextColor(Color.rgb(24, 35, 30));
        name.setTypeface(null, 1);
        header.addView(name, weightParams(1, 0));

        Button delete = createButton("삭제", Color.rgb(247, 230, 227), Color.rgb(150, 41, 31));
        delete.setMinWidth(dp(72));
        delete.setOnClickListener(v -> {
            items.remove(item);
            saveItems();
            renderList();
        });
        header.addView(delete, new LinearLayout.LayoutParams(dp(76), dp(42)));

        card.addView(createInfoLine("구분", item.market == Market.DOMESTIC ? "국내" : "해외"), matchWrapParams(0, dp(10)));
        card.addView(createInfoLine("수량", amountFormat.format(item.quantity) + "주"));
        card.addView(createInfoLine("구매가", formatMoney(item.purchasePrice)));
        card.addView(createInfoLine("구매금액", formatMoney(item.purchaseAmount())));
        card.addView(createInfoLine("현재가", item.currentPrice == null ? "불러오는 중" : formatMoney(item.currentPrice)));

        double currentValue = item.currentPrice == null ? 0 : item.currentPrice * item.quantity;
        double profitAmount = currentValue - item.purchaseAmount();
        TextView profit = createInfoLine("평가금액 / 손익", item.currentPrice == null
                ? "-"
                : formatMoney(currentValue) + " / " + signedMoney(profitAmount));
        if (item.currentPrice != null) {
            profit.setTextColor(profitAmount >= 0 ? Color.rgb(204, 47, 42) : Color.rgb(38, 95, 181));
        }
        card.addView(profit);

        if (item.errorMessage != null) {
            TextView error = new TextView(this);
            error.setText(item.errorMessage);
            error.setTextSize(13);
            error.setTextColor(Color.rgb(164, 86, 40));
            error.setPadding(0, dp(8), 0, 0);
            card.addView(error);
        }
        return card;
    }

    private TextView createInfoLine(String label, String value) {
        TextView text = new TextView(this);
        text.setText(label + ": " + value);
        text.setTextSize(15);
        text.setTextColor(Color.rgb(65, 76, 70));
        text.setPadding(0, dp(4), 0, 0);
        return text;
    }

    private void updateSummary() {
        double invested = 0;
        double currentValue = 0;
        for (StockItem item : items) {
            invested += item.purchaseAmount();
            if (item.currentPrice != null) {
                currentValue += item.currentPrice * item.quantity;
            }
        }
        summaryText.setText("총 " + items.size() + "개 | 구매금액 " + formatMoney(invested)
                + " | 현재 평가 " + formatMoney(currentValue));
    }

    private void startAutoRefresh() {
        stopAutoRefresh();
        autoRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (screenVisible) {
                    refreshAllPrices(false);
                    mainHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
                }
            }
        };
        mainHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    private void stopAutoRefresh() {
        if (autoRefreshRunnable != null) {
            mainHandler.removeCallbacks(autoRefreshRunnable);
            autoRefreshRunnable = null;
        }
    }

    private void refreshAllPrices(boolean manual) {
        if (items.isEmpty()) {
            updateSummary();
            return;
        }
        if (refreshInProgress) {
            if (manual) {
                Toast.makeText(this, "이미 현재가를 갱신하는 중입니다.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        refreshInProgress = true;
        updateRefreshButtonState();
        if (manual) {
            Toast.makeText(this, "현재가를 불러오는 중입니다.", Toast.LENGTH_SHORT).show();
        }

        List<StockItem> refreshTargets = new ArrayList<>(items);
        executor.execute(() -> {
            for (StockItem item : refreshTargets) {
                item.errorMessage = null;
                try {
                    item.currentPrice = fetchCurrentPrice(item);
                    item.errorMessage = item.currentPrice == null ? currentPriceErrorMessage(item.market) : null;
                } catch (Exception e) {
                    item.errorMessage = "현재가 API 호출에 실패했습니다.";
                }
            }
            mainHandler.post(() -> {
                refreshInProgress = false;
                updateRefreshButtonState();
                saveItems();
                renderList();
            });
        });
    }

    private void updateRefreshButtonState() {
        if (topRefreshButton != null) {
            topRefreshButton.setEnabled(!refreshInProgress);
            topRefreshButton.setText(refreshInProgress ? "갱신 중" : "API 갱신");
        }
    }

    private void refreshPrice(StockItem item) {
        executor.execute(() -> {
            try {
                Double price = fetchCurrentPrice(item);
                mainHandler.post(() -> {
                    item.currentPrice = price;
                    item.errorMessage = price == null ? currentPriceErrorMessage(item.market) : null;
                    saveItems();
                    renderList();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    item.errorMessage = "현재가 API 호출에 실패했습니다.";
                    renderList();
                });
            }
        });
    }

    private String currentPriceErrorMessage(Market market) {
        return market == Market.DOMESTIC
                ? "국내 현재가를 찾지 못했습니다. 예: 삼성전자, 005930처럼 입력해보세요."
                : "해외 현재가를 찾지 못했습니다. 예: AAPL처럼 입력해보세요.";
    }

    private Double fetchCurrentPrice(StockItem item) throws Exception {
        return item.market == Market.DOMESTIC
                ? fetchDomesticCurrentPrice(item.name)
                : fetchOverseasCurrentPrice(item.name);
    }

    private Double fetchDomesticCurrentPrice(String stockName) throws Exception {
        String code = normalizeDomesticCode(extractSymbol(stockName));
        if (!isDomesticCode(code)) {
            code = searchDomesticSymbol(code);
        }
        if (!isDomesticCode(code)) {
            return null;
        }

        String[] urls = new String[]{
                "https://m.stock.naver.com/api/stock/" + code + "/basic",
                "https://api.stock.naver.com/stock/" + code + "/basic"
        };
        Exception lastException = null;
        for (String urlText : urls) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlText);
                connection = (HttpURLConnection) url.openConnection();
                configureFinanceConnection(connection);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    Double price = parseDomesticPrice(new JSONObject(readAll(reader)));
                    if (price != null) {
                        return price;
                    }
                }
            } catch (Exception e) {
                lastException = e;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        return null;
    }

    private Double fetchOverseasCurrentPrice(String stockName) throws Exception {
        String symbol = extractSymbol(stockName);
        try {
            Double directPrice = fetchYahooPriceForSymbol(symbol);
            if (directPrice != null) {
                return directPrice;
            }
        } catch (Exception ignored) {
        }

        String resolvedSymbol;
        try {
            resolvedSymbol = searchYahooSymbol(symbol);
        } catch (Exception ignored) {
            return null;
        }
        if (resolvedSymbol == null || resolvedSymbol.trim().isEmpty()) {
            return null;
        }
        return fetchYahooPriceForSymbol(resolvedSymbol);
    }

    private Double fetchYahooPriceForSymbol(String symbol) throws Exception {
        String encodedSymbol = URLEncoder.encode(symbol, "UTF-8");
        URL url = new URL("https://query1.finance.yahoo.com/v8/finance/chart/"
                + encodedSymbol + "?range=1d&interval=1d");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        configureFinanceConnection(connection);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            JSONObject root = new JSONObject(readAll(reader));
            JSONObject meta = root.getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0)
                    .getJSONObject("meta");
            double price;
            if (meta.has("regularMarketPrice")) {
                price = meta.getDouble("regularMarketPrice");
            } else {
                price = meta.optDouble("previousClose", Double.NaN);
            }
            return Double.isNaN(price) ? null : price;
        } finally {
            connection.disconnect();
        }
    }

    private String searchYahooSymbol(String query) throws Exception {
        List<StockSuggestion> suggestions = fetchYahooStockSuggestions(query, 1);
        return suggestions.isEmpty() ? null : suggestions.get(0).symbol;
    }

    private String searchDomesticSymbol(String query) throws Exception {
        List<StockSuggestion> local = findLocalStockSuggestions(query, Market.DOMESTIC, 1);
        if (!local.isEmpty()) {
            return local.get(0).symbol;
        }
        List<StockSuggestion> remote = fetchDomesticStockSuggestions(query, 1);
        return remote.isEmpty() ? null : remote.get(0).symbol;
    }

    private List<StockSuggestion> fetchYahooStockSuggestions(String query, int limit) throws Exception {
        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        URL url = new URL("https://query2.finance.yahoo.com/v1/finance/search?q="
                + encodedQuery + "&quotesCount=" + limit + "&newsCount=0");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        configureFinanceConnection(connection);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            JSONArray quotes = new JSONObject(readAll(reader)).optJSONArray("quotes");
            List<StockSuggestion> suggestions = new ArrayList<>();
            if (quotes == null) {
                return suggestions;
            }

            for (int i = 0; i < quotes.length() && suggestions.size() < limit; i++) {
                JSONObject quote = quotes.getJSONObject(i);
                String symbol = quote.optString("symbol", "").trim();
                String name = quote.optString("shortname", quote.optString("longname", "")).trim();
                String exchange = quote.optString("exchDisp", "").trim();
                if (!symbol.isEmpty()) {
                    suggestions.add(new StockSuggestion(symbol, name.isEmpty() ? symbol : name, exchange, Market.OVERSEAS));
                }
            }
            return suggestions;
        } finally {
            connection.disconnect();
        }
    }

    private List<StockSuggestion> fetchDomesticStockSuggestions(String query, int limit) throws Exception {
        String encodedQuery = URLEncoder.encode(query, "UTF-8");
        URL url = new URL("https://ac.finance.naver.com/ac?q=" + encodedQuery
                + "&q_enc=UTF-8&st=111&r_lt=111&r_format=json");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        configureFinanceConnection(connection);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            String body = readAll(reader).trim();
            List<StockSuggestion> suggestions = new ArrayList<>();
            collectDomesticSuggestions(body, suggestions, limit);
            return suggestions;
        } finally {
            connection.disconnect();
        }
    }

    private void collectDomesticSuggestions(String body, List<StockSuggestion> suggestions, int limit) throws Exception {
        if (body.isEmpty()) {
            return;
        }
        String json = trimJson(body);
        if (json.startsWith("{")) {
            collectDomesticSuggestionsFromObject(new JSONObject(json), suggestions, limit);
        } else if (json.startsWith("[")) {
            collectDomesticSuggestionsFromArray(new JSONArray(json), suggestions, limit);
        }
    }

    private void collectDomesticSuggestionsFromObject(JSONObject object,
                                                      List<StockSuggestion> suggestions,
                                                      int limit) throws Exception {
        if (suggestions.size() >= limit) {
            return;
        }

        String code = firstNonEmpty(
                object.optString("code", ""),
                object.optString("symbol", ""),
                object.optString("itemCode", ""),
                object.optString("stockCode", "")
        );
        String name = firstNonEmpty(
                object.optString("name", ""),
                object.optString("korName", ""),
                object.optString("stockName", ""),
                object.optString("itemName", "")
        );
        String market = firstNonEmpty(
                object.optString("market", ""),
                object.optString("marketName", ""),
                object.optString("exchange", "")
        );
        addDomesticSuggestion(suggestions, code, name, market, limit);

        JSONArray names = object.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length() && suggestions.size() < limit; i++) {
            Object value = object.opt(names.getString(i));
            if (value instanceof JSONObject) {
                collectDomesticSuggestionsFromObject((JSONObject) value, suggestions, limit);
            } else if (value instanceof JSONArray) {
                collectDomesticSuggestionsFromArray((JSONArray) value, suggestions, limit);
            }
        }
    }

    private void collectDomesticSuggestionsFromArray(JSONArray array,
                                                     List<StockSuggestion> suggestions,
                                                     int limit) throws Exception {
        if (suggestions.size() >= limit) {
            return;
        }

        String code = "";
        String name = "";
        String market = "";
        for (int i = 0; i < array.length() && suggestions.size() < limit; i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                collectDomesticSuggestionsFromObject((JSONObject) value, suggestions, limit);
            } else if (value instanceof JSONArray) {
                JSONArray nested = (JSONArray) value;
                addDomesticSuggestionFromFlatArray(nested, suggestions, limit);
                collectDomesticSuggestionsFromArray(nested, suggestions, limit);
            } else if (value instanceof String) {
                String text = ((String) value).trim();
                if (isDomesticCode(text)) {
                    code = text;
                } else if (name.isEmpty() && !text.isEmpty()) {
                    name = text;
                } else if (market.isEmpty() && !text.isEmpty()) {
                    market = text;
                }
            }
        }
        addDomesticSuggestion(suggestions, code, name, market, limit);
    }

    private void addDomesticSuggestionFromFlatArray(JSONArray array,
                                                    List<StockSuggestion> suggestions,
                                                    int limit) {
        String code = "";
        String name = "";
        String market = "";
        for (int i = 0; i < array.length(); i++) {
            String text = array.optString(i, "").trim();
            if (isDomesticCode(text)) {
                code = text;
            } else if (name.isEmpty() && looksLikeStockName(text)) {
                name = text;
            } else if (market.isEmpty() && (text.contains("KOSPI") || text.contains("KOSDAQ") || text.contains("코스"))) {
                market = text;
            }
        }
        addDomesticSuggestion(suggestions, code, name, market, limit);
    }

    private void addDomesticSuggestion(List<StockSuggestion> suggestions,
                                       String code,
                                       String name,
                                       String market,
                                       int limit) {
        code = normalizeDomesticCode(code);
        if (!isDomesticCode(code) || name == null || name.trim().isEmpty() || suggestions.size() >= limit) {
            return;
        }
        if (!containsSymbol(suggestions, code)) {
            suggestions.add(new StockSuggestion(code, name.trim(), market == null ? "" : market.trim(), Market.DOMESTIC));
        }
    }

    private boolean looksLikeStockName(String text) {
        return !text.isEmpty()
                && !text.startsWith("http")
                && !text.contains("<")
                && !isDomesticCode(text);
    }

    private String trimJson(String body) {
        int firstObject = body.indexOf('{');
        int firstArray = body.indexOf('[');
        int start;
        if (firstObject < 0) {
            start = firstArray;
        } else if (firstArray < 0) {
            start = firstObject;
        } else {
            start = Math.min(firstObject, firstArray);
        }
        int endObject = body.lastIndexOf('}');
        int endArray = body.lastIndexOf(']');
        int end = Math.max(endObject, endArray);
        if (start >= 0 && end >= start) {
            return body.substring(start, end + 1);
        }
        return body;
    }

    private void configureFinanceConnection(HttpURLConnection connection) throws Exception {
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Referer", "https://m.stock.naver.com/");
    }

    private String readAll(BufferedReader reader) throws Exception {
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        return body.toString();
    }

    private String extractSymbol(String input) {
        int open = input.lastIndexOf('(');
        int close = input.lastIndexOf(')');
        if (open >= 0 && close > open) {
            return input.substring(open + 1, close).trim();
        }
        return input;
    }

    private String normalizeDomesticCode(String raw) {
        if (raw == null) {
            return "";
        }
        String code = raw.trim();
        if (code.endsWith(".KS") || code.endsWith(".KQ")) {
            code = code.substring(0, code.length() - 3);
        }
        return code;
    }

    private boolean isDomesticCode(String code) {
        return code != null && code.toUpperCase(Locale.ROOT).matches("[0-9A-Z]{6}");
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private Double parsePrice(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Double parseDomesticPrice(JSONObject root) {
        String[] keys = new String[]{
                "closePrice",
                "stockEndPrice",
                "nowVal",
                "currentPrice",
                "price"
        };
        for (String key : keys) {
            Double price = parsePrice(root.optString(key, null));
            if (price != null) {
                return price;
            }
        }
        return null;
    }

    private void loadItems() {
        items.clear();
        String raw = preferences.getString(KEY_ITEMS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String name = object.getString("name");
                StockItem item = new StockItem(
                        name,
                        object.getDouble("quantity"),
                        object.has("purchasePrice")
                                ? object.getDouble("purchasePrice")
                                : object.getDouble("totalPrice"),
                        parseMarket(object.optString("market", ""), name)
                );
                if (object.has("currentPrice") && !object.isNull("currentPrice")) {
                    item.currentPrice = object.getDouble("currentPrice");
                }
                items.add(item);
            }
        } catch (Exception ignored) {
            items.clear();
        }
    }

    private Market parseMarket(String raw, String name) {
        if ("DOMESTIC".equals(raw)) {
            return Market.DOMESTIC;
        }
        if ("OVERSEAS".equals(raw)) {
            return Market.OVERSEAS;
        }
        return isDomesticCode(normalizeDomesticCode(extractSymbol(name))) ? Market.DOMESTIC : Market.OVERSEAS;
    }

    private void saveItems() {
        JSONArray array = new JSONArray();
        for (StockItem item : items) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", item.name);
                object.put("quantity", item.quantity);
                object.put("purchasePrice", item.purchasePrice);
                object.put("totalPrice", item.purchaseAmount());
                object.put("market", item.market.name());
                object.put("currentPrice", item.currentPrice == null ? JSONObject.NULL : item.currentPrice);
                array.put(object);
            } catch (Exception ignored) {
            }
        }
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply();
    }

    private Double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String formatMoney(double value) {
        return amountFormat.format(value) + "원";
    }

    private String signedMoney(double value) {
        String prefix = value > 0 ? "+" : "";
        return prefix + amountFormat.format(value) + "원";
    }

    private LinearLayout.LayoutParams matchWrapParams(int left, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(left, top, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weightParams(float weight, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight
        );
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum Market {
        DOMESTIC,
        OVERSEAS
    }

    private static class StockItem {
        final String name;
        final double quantity;
        final double purchasePrice;
        final Market market;
        Double currentPrice;
        String errorMessage;

        StockItem(String name, double quantity, double purchasePrice, Market market) {
            this.name = name;
            this.quantity = quantity;
            this.purchasePrice = purchasePrice;
            this.market = market;
        }

        double purchaseAmount() {
            return purchasePrice * quantity;
        }
    }

    private static class StockSuggestion {
        final String symbol;
        final String name;
        final String exchange;
        final Market market;

        StockSuggestion(String symbol, String name, String exchange, Market market) {
            this.symbol = symbol;
            this.name = name;
            this.exchange = exchange;
            this.market = market;
        }

        String displayText() {
            String marketText = exchange.isEmpty() ? "" : " · " + exchange;
            return name + " (" + symbol + ")" + marketText;
        }

        String inputText() {
            return name + " (" + symbol + ")";
        }
    }
}
