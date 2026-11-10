package ir.yasir.diag;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.TypedValue;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Iterator;

public class MainActivity extends Activity {
  final Handler ui = new Handler(Looper.getMainLooper());
  volatile boolean running = false;
  volatile int connectionEpoch = 0;
  int malformedFrames = 0, telemetryFrames = 0, prideFrames = 0;
  Socket socket;
  OutputStream out;
  TextView status, lastDiagnosticStatus, rpm, coolant, intake, battery, map, injectors, spark, dtc;
  // Keep the top connection badge at a fixed size and show only real TCP state.
  final Runnable showRealTcpLoss = () -> displayTcpBadge(false);
  LinearLayout content, root, home, parametersMenu, speedSection, emissionsSection, ignitionSection, airSection, fuelSection, dtcSection, ecuSection, actuatorSection, resetSection, chartSection;
  LinearLayout appBrandHeader;
  LinearLayout currentScreen, dynamicParametersSection, dynamicParameterList, systemSelectionSection, ecuSelectionSection, autoSearchSection, diagnosticMenu, settingsSection, updateSection, helpSection;
  LinearLayout dynamicReturnTarget;
  ScrollView scrollContainer;
  final HashMap<String, Integer> screenScrollPositions = new HashMap<>();
  String activeScreenKey = "home";
  String lastUiStatus = "";
  long lastUiStatusAt = 0;
  boolean tcpLive = false;
  volatile boolean scanAfterTcpConnect = false;
  boolean autoDetectionActive = false;
  boolean autoCanConnected = false;
  int autoDetectionStage = 0;
  int autoDetectionToken = 0;
  boolean ecuActionsShownForCurrentConnection = false;
  boolean exiting = false;
  final HashMap<Integer, Bitmap> vehiclePhotoCache = new HashMap<>();
  final HashMap<Integer, Boolean> vehiclePhotoLoading = new HashMap<>();
  TextView selectedBrandLabel, diagnosticSelectionLabel, prideSiemensUpdated, autoSearchStatus;
  Button prideSiemensChoice, boschZamyadChoice;
  LinearLayout boschServiceSection, sb13CanSection;
  TextView sb13CanUpdated;
  TextView boschFaultValues;
  LinearLayout prideSiemensSection, boschZamyadSection;
  TextView boschZamyadUpdated;
  final HashMap<String, TextView> boschZamyadValues = new HashMap<>();
  volatile String pendingProfileCommand = "";
  volatile String pendingPostConnectCommand = "";
  final LinearLayout[] prideGroupSections = new LinearLayout[4];
  final String[] prideCurrentValues = new String[35];
  final String[] pridePendingValues = new String[35];
  final long[] prideLastSeenAt = new long[35];
  long prideLastCompleteAt = 0;
  final java.util.concurrent.ExecutorService prideAckWorker =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  final boolean[] pridePendingPresent = new boolean[35];
  int pridePendingCycle = -1;
  int prideCompleteCycles = 0;
  int prideIncompleteCycles = 0;
  int prideInvalidValues = 0;
  final HashMap<String, TextView> prideSiemensValues = new HashMap<>();
  String selectedBrand = "", selectedEcu = "", selectedSystemType = "";
  TextView updateInfo;
  SharedPreferences preferences;
  float uiScale = 1.0f;
  int themeMode = 3;
  final HashMap<String, TextView> dynamicParameterValues = new HashMap<>();
  final HashMap<String, TextView> dynamicParameterNames = new HashMap<>();
  final HashMap<String, TextView> dynamicParameterStatuses = new HashMap<>();
  final HashMap<String, Integer> dynamicParameterRanks = new HashMap<>();
  TextView[] injectorValues = new TextView[5];
  TextView[] sparkValues = new TextView[4];
  TextView injectorAverage, dashRpm, dashCoolant, dashBattery, dashIntake, dashInjection, dashUpdated;
  LinearLayout dashboardSection;
  private static final String[] PRIDE_IDS = {
    "emax_pride_battery",
    "emax_pride_air_temp",
    "emax_pride_tps_voltage",
    "emax_pride_tps_angle",
    "emax_pride_map",
    "emax_pride_rpm",
    "emax_pride_stepper_actual",
    "emax_pride_canister_ocr",
    "emax_pride_canister_load",
    "emax_pride_stepper_target",
    "emax_pride_engine_load",
    "emax_pride_vehicle_speed",
    "emax_pride_injection_time",
    "emax_pride_mileage",
    "emax_pride_gear",
    "emax_pride_speed_disconnects",
    "emax_pride_speed_error_runtime",
    "emax_pride_o2_voltage_1",
    "emax_pride_o2_voltage_2",
    "emax_pride_mixture_status",
    "emax_pride_o2_correction",
    "emax_pride_coolant",
    "emax_pride_ac_switch",
    "emax_pride_ac_control",
    "emax_pride_ac_relay",
    "emax_pride_fan_status",
    "emax_pride_injector_1",
    "emax_pride_injector_2",
    "emax_pride_injector_3",
    "emax_pride_injector_4",
    "emax_pride_injector_average",
    "emax_pride_spark_1",
    "emax_pride_spark_2",
    "emax_pride_spark_3",
    "emax_pride_spark_4"
  };
  private static final String[] PRIDE_UNITS = {
    "V",
    "°C",
    "V",
    "°",
    "mbar",
    "rpm",
    "",
    "%",
    "",
    "",
    "",
    "km/h",
    "ms",
    "km",
    "",
    "بار",
    "h",
    "V",
    "V",
    "",
    "",
    "°C",
    "",
    "",
    "",
    "",
    "ms",
    "ms",
    "ms",
    "ms",
    "ms",
    "°",
    "°",
    "°",
    "°"
  };
  final String host = "192.168.4.1";
  final int port = 3333;
  int navy = Color.rgb(13,26,44), teal = Color.rgb(0,135,135);

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    preferences = getSharedPreferences("yasir_diag_settings", MODE_PRIVATE);
    final String reopenScreenKey = "home";
    installCrashDiagnostics();
    uiScale = preferences.getFloat("ui_scale", 1.0f);
    if (preferences.getInt("industrial_ui_v1", 0) == 0) {
      themeMode = 1;
      preferences.edit().putInt("theme_mode", 1).putInt("industrial_ui_v1", 1).apply();
    } else {
      themeMode = preferences.getInt("theme_mode", 1);
    }
    configurePalette();
    getWindow().setStatusBarColor(navy);
    LinearLayout appShell = new LinearLayout(this);
    appShell.setOrientation(LinearLayout.VERTICAL);
    appShell.setBackgroundColor(Color.rgb(8, 20, 35));
    ScrollView scroll = new ScrollView(this);
    scrollContainer = scroll;
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    root = new LinearLayout(this);
    root.setPadding(dp(10),dp(10),dp(10),dp(30));
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(8, 20, 35));
    root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    scroll.addView(root);
    appShell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(appShell);
    content = root;
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(8), dp(7), dp(8), dp(7));
    header.setTag("hero-header");
    header.setBackground(panelDrawable(Color.rgb(12, 28, 46), Color.rgb(78, 112, 142), 0));
    appBrandHeader = header;
    LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
    headerParams.setMargins(0, 0, 0, dp(10));
    ImageView brandLogo = new ImageView(this);
    brandLogo.setImageResource(R.drawable.yasir_diag_logo);
    brandLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
    brandLogo.setBackground(panelDrawable(Color.rgb(16, 34, 53), Color.rgb(100, 128, 151), 0));
    brandLogo.setClipToOutline(true);
    header.addView(brandLogo, new LinearLayout.LayoutParams(dp(112), dp(112)));
    LinearLayout brandText = new LinearLayout(this);
    brandText.setOrientation(LinearLayout.VERTICAL);
    brandText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
    brandText.setPadding(dp(8), 0, dp(6), 0);
    LinearLayout headerActions = new LinearLayout(this);
    headerActions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    headerActions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    String[] topIcons = {"⚙", "ⓘ", "☰"};
    Runnable[] topActions = {() -> show(settingsSection), () -> show(helpSection), () -> show(diagnosticMenu)};
    for (int ai = 0; ai < topIcons.length; ai++) {
      final int actionIndex = ai;
      TextView action = text(topIcons[ai], 20, true);
      action.setTextColor(Color.rgb(180, 211, 235));
      action.setGravity(Gravity.CENTER);
      action.setPadding(dp(7), dp(2), dp(7), dp(2));
      action.setOnClickListener(v -> topActions[actionIndex].run());
      headerActions.addView(action);
    }
    brandText.addView(headerActions, new LinearLayout.LayoutParams(-1, -2));
    TextView appTitle = text("یاسر دیاگ", 27, true);
    appTitle.setTextColor(Color.rgb(241, 247, 252));
    brandText.addView(appTitle);
    TextView appTagline = text("تخصص ما، آرامش خودروی شما", 12, false);
    appTagline.setTextColor(Color.rgb(167, 190, 211));
    brandText.addView(appTagline);
    header.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(header, headerParams);

    LinearLayout connectionPanel = new LinearLayout(this);
    connectionPanel.setOrientation(LinearLayout.HORIZONTAL);
    connectionPanel.setGravity(Gravity.CENTER_VERTICAL);
    connectionPanel.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    connectionPanel.setPadding(dp(5), dp(5), dp(5), dp(5));
    connectionPanel.setTag("connection-panel");
    connectionPanel.setMinimumHeight(dp(68));
    connectionPanel.setBackground(panelDrawable(Color.rgb(12, 28, 46), Color.rgb(78, 112, 142), 0));
    TextView connectAction = text("▣ اتصال به دستگاه", 12, true);
    connectAction.setTextColor(Color.WHITE);
    connectAction.setGravity(Gravity.CENTER);
    connectAction.setPadding(dp(5), dp(8), dp(5), dp(8));
    connectAction.setBackground(panelDrawable(Color.rgb(31, 83, 126), Color.rgb(91, 132, 165), 0));
    connectAction.setOnClickListener(v -> connect());
    connectionPanel.addView(connectAction, new LinearLayout.LayoutParams(0, -2, 1.05f));
    TextView endpoint = text("E-MAX\n192.168.4.1:3333", 10, true);
    endpoint.setTextColor(Color.rgb(208, 222, 235));
    endpoint.setGravity(Gravity.CENTER);
    connectionPanel.addView(endpoint, new LinearLayout.LayoutParams(0, -2, 1.0f));
    status = text("● TCP\nقطع", 10, true);
    status.setTextColor(Color.rgb(238, 181, 117));
    status.setGravity(Gravity.CENTER);
    status.setLines(2);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    status.setIncludeFontPadding(false);
    connectionPanel.addView(status, new LinearLayout.LayoutParams(0, dp(52), 1.0f));
    root.addView(connectionPanel);

    home = section("");
    parametersMenu = section("پارامترها");
    dashboardSection = section("داشبورد زنده موتور");
    speedSection = section("اطلاعات سیگنال کیلومترشمار");
    emissionsSection = section("اطلاعات کنترل آلودگی");
    ignitionSection = section("سیگنال جرقه");
    airSection = section("اطلاعات مکش هوا");
    fuelSection = section("اطلاعات تزریق سوخت");
    dtcSection = section("خطاهای ECU");
    ecuSection = section("شناسایی ECU");
    actuatorSection = section("عملگرها");
    resetSection = section("ریست مقادیر سازگار یافته");
    chartSection = section("نمودارها");
    dynamicParametersSection = section("");
    prideSiemensSection = section("ECU زیمنس پراید • ۳۵ پارامتر زنده");
    boschZamyadSection = section("ECU بوش زامیاد 7.9.7.1 • داده زنده آزمایشی");
    boschServiceSection = section("ECU بوش زامیاد 7.9.7.1 — خطاها و عملگرها");
    sb13CanSection = section("");
    systemSelectionSection = section("انتخاب سیستم و یونیت");
    autoSearchSection = section("جستجوی خودکار ECU");
    ecuSelectionSection = section("انتخاب ECU یا یونیت");
    diagnosticMenu = section("منوی عیب‌یابی");
    settingsSection = section("تنظیمات ظاهری و برنامه");
    updateSection = section("به‌روزرسانی برنامه");
    helpSection = section("راهنما و اطلاعات برنامه");

    content = home;
    content.addView(sectionTitle("انتخاب سازنده خودرو", "برند خودرو را انتخاب کنید تا ECU و یونیت‌ها را ببینید", "▣"));
    addBrandDirectoryRow(home, "ایران خودرو", "IKCO", "سمند، پژو، دنا و خانواده ملی");
    addBrandDirectoryRow(home, "سایپا", "SAIPA", "پراید، تیبا، ساینا، کوییک و شاهین");
    addBrandDirectoryRow(home, "پارس خودرو", "PARS", "محصولات پارس‌خودرو و پلتفرم‌های مشترک");
    addBrandDirectoryRow(home, "زامیاد", "ZAMYAD", "نیسان زامیاد • بنزینی و دوگانه‌سوز");
    addBrandDirectoryRow(home, "بهمن", "BAHMAN", "سواری، وانت و خودروهای تجاری");
    addBrandDirectoryRow(home, "کرمان موتور", "KMC", "جک، کی‌ام‌سی و خودروهای مونتاژی");
    addBrandDirectoryRow(home, "مدیران خودرو", "MVM", "ام‌وی‌ام، چری و فونیکس");
    addBrandDirectoryRow(home, "رنو", "RENAULT", "تندر، ساندرو و خانواده رنو");
    addBrandDirectoryRow(home, "پژو", "PEUGEOT", "پلتفرم‌ها و ECUهای خانواده پژو");
    addBrandDirectoryRow(home, "نیسان", "NISSAN", "نیسان سواری و تجاری");
    addBrandDirectoryRow(home, "تویوتا", "TOYOTA", "خودروهای سواری و شاسی‌بلند");
    addBrandDirectoryRow(home, "هیوندای", "HYUNDAI", "سواری و کراس‌اوور");
    addBrandDirectoryRow(home, "کیا", "KIA", "سواری و کراس‌اوور");
    addBrandDirectoryRow(home, "مزدا", "MAZDA", "سواری و وانت");
    addBrandDirectoryRow(home, "سایر خودروها", "OTHER", "برندهای دیگر و فهرست عمومی ECU");

    content = sb13CanSection;
    styleBlueWorkshopPage(sb13CanSection);
    blueWorkshopHeader("یاسر دیاگ", "نیسان زامیاد • SB13 بنزینی یورو ۵");
    sb13CanUpdated = text("هنوز پارامتر زنده‌ای دریافت نشده است", 13, false);
    sb13CanUpdated.setGravity(Gravity.CENTER);
    sb13CanUpdated.setTextColor(Color.rgb(197, 223, 255));
    content.addView(sb13CanUpdated);
    blueWorkshopRow(content, "جستجوی خودکار ECU روی CAN", "⌕", () -> send("!1"));
    blueWorkshopRow(content, "SB13 بنزینی یورو ۵", "⚡", () -> send("!1"));
    blueWorkshopRow(content, "خواندن پارامترهای زنده", "▤", () -> {
      dynamicReturnTarget = diagnosticMenu;
      show(dynamicParametersSection);
      prepareSb13Live();
    });
    blueWorkshopRow(content, "خواندن خطاهای ECU", "⚠", () -> { show(dtcSection); send("!2"); });
    blueWorkshopRow(content, "پاک‌کردن خطاهای ECU", "×", () -> confirmClear());
    blueWorkshopRow(content, "قطع ارتباط CAN", "⏻", () -> send("!0"));
    blueWorkshopRow(content, "بازگشت", "‹", () ->
        show(ecuActionsShownForCurrentConnection ? diagnosticMenu : ecuSelectionSection));

    content = ecuSelectionSection;
    selectedBrandLabel = text("برند و سیستم انتخابی",18,true);
    content.addView(selectedBrandLabel);
    content.addView(text("مدل ECU یا یونیت را انتخاب کنید. گزینه‌های دارای نشان «پروفایل فعال» در این نسخه پشتیبانی عملیاتی دارند؛ بقیه فقط در کاتالوگ نمایش داده می‌شوند.",14,false));
    back(systemSelectionSection);

    content = autoSearchSection;
    styleBlueWorkshopPage(autoSearchSection);
    blueWorkshopHeader("یاسر دیاگ", "شناسایی خودکار ECU • شبکه CAN");
    autoSearchStatus = text("آمادهٔ جستجو\nبرند انتخابی: " + (selectedBrand.isEmpty() ? "انتخاب نشده" : selectedBrand), 15, true);
    autoSearchStatus.setTextColor(Color.rgb(197, 223, 255));
    autoSearchStatus.setGravity(Gravity.CENTER);
    autoSearchStatus.setPadding(dp(10), dp(18), dp(10), dp(18));
    autoSearchSection.addView(autoSearchStatus);
    blueWorkshopRow(autoSearchSection, "شروع / تکرار جستجوی خودکار", "CAN", () -> startAutomaticCanScan());
    blueWorkshopRow(autoSearchSection, "خواندن پارامترهای زنده", "▤", () -> {
      dynamicReturnTarget = autoSearchSection;
      show(dynamicParametersSection);
      prepareSb13Live();
    });
    blueWorkshopRow(autoSearchSection, "خواندن خطاهای ECU", "DTC", () -> { show(dtcSection); send("!2"); });
    blueWorkshopRow(autoSearchSection, "انتخاب دستی ECU یا یونیت", "≡", () -> show(systemSelectionSection));
    blueWorkshopRow(autoSearchSection, "بازگشت به برندها", "‹", () -> show(home));

    content = diagnosticMenu;
    content.addView(sectionTitle("عیب‌یابی ECU", "عملیات برای ECU شناسایی‌شده", "ECU"));
    diagnosticSelectionLabel = text("خودرو: —   |   ECU: —",15,true);
    content.addView(diagnosticSelectionLabel);
    blueWorkshopRow(diagnosticMenu, "خواندن عیب", "DTC", () -> openIdentifiedEcuFaults());
    blueWorkshopRow(diagnosticMenu, "پاک کردن عیب", "CLR", () -> confirmIdentifiedEcuClear());
    blueWorkshopRow(diagnosticMenu, "پارامترها", "LIVE", () -> openIdentifiedEcuParameters());
    blueWorkshopRow(diagnosticMenu, "عملکردها", "ACT", () -> openIdentifiedEcuActuators());
    blueWorkshopRow(diagnosticMenu, "ریست مقادیر سازگار یافته", "RESET", () -> show(resetSection));
    blueWorkshopRow(diagnosticMenu, "نمودار", "GRAPH", () -> show(chartSection));
    blueWorkshopRow(diagnosticMenu, "تغییر خودرو / ECU", "‹", () -> show(home));
    back(home);

    content = settingsSection;
    content.addView(text("ظاهر صنعتی برنامه",18,true));
    content.addView(text("تغییرات در همین گوشی ذخیره می‌شود و پس از بستن و بازکردن برنامه باقی می‌ماند.",14,false));
    button("رنگ‌بندی: روشن صنعتی", () -> setThemeMode(0));
    button("رنگ‌بندی: آبی نفتی تیره", () -> setThemeMode(1));
    button("رنگ‌بندی: سبز نئونی تیره", () -> setThemeMode(3));
    button("رنگ‌بندی: سبز روشن", () -> setThemeMode(2));
    button("اندازه نوشته‌ها: کوچک", () -> setUiScale(0.90f));
    button("اندازه نوشته‌ها: استاندارد", () -> setUiScale(1.0f));
    button("اندازه نوشته‌ها: بزرگ", () -> setUiScale(1.15f));
    button("اندازه نوشته‌ها: خیلی بزرگ", () -> setUiScale(1.30f));
    button("بازگردانی تنظیمات ظاهری", () -> { setThemeMode(0); setUiScale(1.0f); });
    back(home);

    content = updateSection;
    updateInfo = text("در حال آماده‌سازی اطلاعات نسخه...",15,false);
    content.addView(updateInfo);
    button("بررسی نسخه منتشرشده", () -> checkForUpdates());
    button("باز کردن صفحه رسمی نسخه‌ها", () -> openReleasePage());
    content.addView(text("برای نصب نسخه جدید، فایل APK را فقط از صفحه رسمی پروژه دریافت کنید. نسخه فعلی را تا پیش از اطمینان از اتصال و عملکرد، حذف نکنید.",14,false));
    back(home);

    content = helpSection;
    content.addView(text("یاسر دیاگ | ابزار عیب‌یابی فارسی خودرو",18,true));
    content.addView(text("نسخه برنامه: 2.6.4 • شناسایی چندمسیره و ECU ID",16,true));
    content.addView(text("ارتباط فعلی: Wi‑Fi به E-MAX، TCP پورت 3333؛ جستجوی خودکار CAN و انتخاب دستی ECU/یونیت.",14,false));
    content.addView(text("شناسایی خودکار، پاسخ OBD-II و مشخصات شبکه CAN را پیدا می‌کند. برای تعیین مدل ECU از انتخاب دستی استفاده کنید.",14,false));
    content.addView(text("نکته ایمنی: پاک‌کردن خطا و تست عملگر را فقط در شرایط تعمیرگاهی مناسب انجام دهید. نمایش یک فرمان به معنی تأیید عملکرد فیزیکی عملگر نیست.",14,false));
    content.addView(text("آخرین پیام ارتباط و عیب‌یابی (جزئیات خارج از نوار ثابت بالا)", 14, true));
    lastDiagnosticStatus = text("هنوز پیامی ثبت نشده", 13, false);
    lastDiagnosticStatus.setMaxLines(3);
    lastDiagnosticStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
    content.addView(lastDiagnosticStatus);
    String previousCrash = preferences.getString("yasir.lastCrash", "");
    if (!previousCrash.isEmpty()) {
      content.addView(text("گزارش آخرین خطای واقعی اندروید:", 15, true));
      content.addView(text(previousCrash, 11, false));
      button("کپی خطای برنامه", () -> {
        android.content.ClipboardManager cm =
          (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(
          android.content.ClipData.newPlainText("YasirCrash", previousCrash));
      });
    }
    button("تنظیمات برنامه", () -> show(settingsSection));
    button("صفحه به‌روزرسانی", () -> { refreshUpdateInfo(); show(updateSection); });
    back(home);

    content = dashboardSection;
    dashRpm = field("دور موتور");
    dashCoolant = field("دمای آب");
    dashIntake = field("دمای هوای ورودی");
    dashBattery = field("ولتاژ باتری");
    dashInjection = field("میانگین زمان پاشش");
    dashUpdated = text("هنوز داده‌ای دریافت نشده است",13,false);
    content.addView(dashUpdated);
    back(home);

    content = parametersMenu;
    button("اطلاعات سیگنال کیلومترشمار", () -> show(speedSection));
    button("اطلاعات کنترل آلودگی", () -> show(emissionsSection));
    button("سیگنال جرقه", () -> show(ignitionSection));
    button("اطلاعات مکش هوا", () -> show(airSection));
    button("اطلاعات تزریق سوخت", () -> show(fuelSection));
    button("ECU زیمنس پراید — ۳۵ پارامتر زنده", () -> show(prideSiemensSection));
    button("ECU بوش زامیاد 7.9.7.1 — ۱۹ پارامتر زنده", () -> show(boschZamyadSection));
    back(home);

    content = speedSection;
    rpm = field("دور موتور");
    back(parametersMenu);

    content = emissionsSection;
    coolant = field("دمای آب");
    battery = field("ولتاژ باتری");
    back(parametersMenu);

    content = ignitionSection;
    for(int i=0;i<4;i++) sparkValues[i] = tableRow("آوانس جرقه سیلندر " + (i+1));
    spark = text("",12,false);
    back(parametersMenu);

    content = airSection;
    intake = field("دمای هوای ورودی");
    map = field("فشار منیفولد (آزمایشی / تأییدنشده)");
    back(parametersMenu);

    content = fuelSection;
    for(int i=0;i<5;i++) injectorValues[i] = tableRow("زمان پاشش انژکتور سیلندر " + (i+1));
    injectorAverage = tableRow("میانگین زمان پاشش");
    injectors = text("",12,false);
    back(parametersMenu);

    content = dtcSection;
    button("خواندن خطاها", () -> send("!2"));
    button("پاک کردن خطاها", () -> confirmClear());
    dtc = field("کد خطا و شرح فارسی");
    back(home);

    content = ecuSection;
    content.addView(text("ECU: Siemens SIM2K-34VR (پروفایل انتخاب‌شده)؛ شناسایی خودکار هنوز پیاده‌سازی نشده است.",16,false));
    back(home);
    content = actuatorSection;
    content.addView(text("تست عملگرهای ECU زیمنس SIM2K-34VR",18,true));
    content.addView(text("شرط ایمنی: خودرو ثابت، موتور خاموش و سوئیچ باز باشد. هر تست یک‌بار فعال می‌شود و فریم‌ور V42 باید برای توقف خودکار ۲٫۵ ثانیه‌ای نصب باشد.",14,false));
    content.addView(text("برای هر تست، برنامه ابتدا فرمان آماده‌سازی (!X) و سپس فرمان همان عملگر را می‌فرستد.",13,false));
    button("توقف اضطراری همه تست‌ها", () -> send("!Z"));
    button("تست استپر موتور", () -> confirmActuator("استپر موتور", 'S', false));
    button("تست رله پمپ بنزین", () -> confirmActuator("رله پمپ بنزین", 'P', false));
    button("تست فن دور کند", () -> confirmActuator("فن دور کند", 'L', false));
    button("تست فن دور تند", () -> confirmActuator("فن دور تند", 'H', false));
    button("تست رله اصلی ECU", () -> confirmActuator("رله اصلی ECU", 'M', false));
    button("تست رله کولر", () -> confirmActuator("رله کولر", 'Q', false));
    button("تست چراغ چک", () -> confirmActuator("چراغ چک موتور", 'G', false));
    button("تست شیر برقی کنیستر", () -> confirmActuator("شیر برقی کنیستر", 'K', false));
    button("تست خروجی با برچسب اگزوز", () -> confirmActuator("خروجی با برچسب اگزوز (شناسه نیازمند تأیید)", 'U', false));
    button("تست کویل ۱", () -> confirmActuator("کویل ۱", 'J', true));
    button("تست کویل ۲", () -> confirmActuator("کویل ۲", 'W', true));
    button("تست انژکتور ۱", () -> confirmActuator("انژکتور ۱", 'N', true));
    button("تست انژکتور ۲", () -> confirmActuator("انژکتور ۲", 'O', true));
    button("تست انژکتور ۳", () -> confirmActuator("انژکتور ۳", 'R', true));
    button("تست انژکتور ۴", () -> confirmActuator("انژکتور ۴", 'Y', true));

    back(home);
    content = resetSection;
    content.addView(text("ریست مقادیر سازگار یافته تا شناسایی و تأیید فرمان ECU غیرفعال است.",16,false));
    back(home);
    content = chartSection;
    content.addView(text("نمودارهای زنده در نسخه بعدی اضافه می‌شوند؛ مقادیر عددی فعلاً در بخش پارامترها در دسترس‌اند.",16,false));
    back(home);
    content = dynamicParametersSection;
    content.addView(text("عظام SB13 • پارامترهای زنده CAN", 18, true));
    blueWorkshopRow(content, "خطاها و عملیات CAN", "DTC", () -> show(sb13CanSection));
    dynamicParameterList = new LinearLayout(this);
    dynamicParameterList.setOrientation(LinearLayout.VERTICAL);
    dynamicParameterList.setPadding(0, dp(6), 0, 0);
    dynamicParametersSection.addView(dynamicParameterList);
    dynamicReturnTarget = home;
    blueWorkshopRow(content, "بازگشت", "‹", () -> show(dynamicReturnTarget == null ? home : dynamicReturnTarget));

    content = prideSiemensSection;
    prideSiemensUpdated = text("آماده دریافت داده از ECU • ۰/۳۵", 12, false);
    content.addView(prideSiemensUpdated);
    button("عیب‌یابی و عملکردهای زیمنس", () -> show(diagnosticMenu));

    prideGroupSections[0] = new LinearLayout(this);
    prideGroupSections[0].setOrientation(LinearLayout.VERTICAL);
    prideGroupSections[0].setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    prideSiemensSection.addView(prideGroupSections[0]);
    content = prideGroupSections[0];
    content.addView(text("سنسورها و عملکرد موتور", 18, true));
    prideSiemensValues.put("emax_pride_battery", prideCompactRow("ولتاژ باتری"));
    prideSiemensValues.put("emax_pride_coolant", prideCompactRow("دمای آب موتور"));
    prideSiemensValues.put("emax_pride_air_temp", prideCompactRow("دمای هوای ورودی"));
    prideSiemensValues.put("emax_pride_tps_voltage", prideCompactRow("ولتاژ سنسور دریچه گاز"));
    prideSiemensValues.put("emax_pride_tps_angle", prideCompactRow("زاویه دریچه گاز"));
    prideSiemensValues.put("emax_pride_map", prideCompactRow("فشار منیفولد MAP"));
    prideSiemensValues.put("emax_pride_rpm", prideCompactRow("دور موتور"));
    prideSiemensValues.put("emax_pride_engine_load", prideCompactRow("بار موتور"));
    prideSiemensValues.put("emax_pride_vehicle_speed", prideCompactRow("سرعت خودرو"));
    prideSiemensValues.put("emax_pride_o2_voltage_1", prideCompactRow("ولتاژ سنسور اکسیژن ۱"));
    prideSiemensValues.put("emax_pride_o2_voltage_2", prideCompactRow("ولتاژ سنسور اکسیژن ۲"));
    prideSiemensValues.put("emax_pride_o2_correction", prideCompactRow("تصحیح سنسور اکسیژن"));
    prideGroupSections[1] = new LinearLayout(this);
    prideGroupSections[1].setOrientation(LinearLayout.VERTICAL);
    prideGroupSections[1].setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    prideSiemensSection.addView(prideGroupSections[1]);
    content = prideGroupSections[1];
    content.addView(text("سوخت‌رسانی و استپر", 18, true));
    prideSiemensValues.put("emax_pride_injection_time", prideCompactRow("زمان پاشش موتور"));
    prideSiemensValues.put("emax_pride_injector_1", prideCompactRow("زمان پاشش انژکتور ۱"));
    prideSiemensValues.put("emax_pride_injector_2", prideCompactRow("زمان پاشش انژکتور ۲"));
    prideSiemensValues.put("emax_pride_injector_3", prideCompactRow("زمان پاشش انژکتور ۳"));
    prideSiemensValues.put("emax_pride_injector_4", prideCompactRow("زمان پاشش انژکتور ۴"));
    prideSiemensValues.put("emax_pride_injector_average", prideCompactRow("میانگین زمان پاشش"));
    prideSiemensValues.put("emax_pride_stepper_actual", prideCompactRow("موقعیت واقعی استپر"));
    prideSiemensValues.put("emax_pride_stepper_target", prideCompactRow("موقعیت هدف استپر"));
    prideSiemensValues.put("emax_pride_canister_ocr", prideCompactRow("فرمان شیر کنیستر"));
    prideSiemensValues.put("emax_pride_canister_load", prideCompactRow("بار کنیستر"));
    prideGroupSections[2] = new LinearLayout(this);
    prideGroupSections[2].setOrientation(LinearLayout.VERTICAL);
    prideGroupSections[2].setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    prideSiemensSection.addView(prideGroupSections[2]);
    content = prideGroupSections[2];
    content.addView(text("جرقه و احتراق", 18, true));
    prideSiemensValues.put("emax_pride_spark_1", prideCompactRow("آوانس جرقه سیلندر ۱"));
    prideSiemensValues.put("emax_pride_spark_2", prideCompactRow("آوانس جرقه سیلندر ۲"));
    prideSiemensValues.put("emax_pride_spark_3", prideCompactRow("آوانس جرقه سیلندر ۳"));
    prideSiemensValues.put("emax_pride_spark_4", prideCompactRow("آوانس جرقه سیلندر ۴"));
    prideSiemensValues.put("emax_pride_mixture_status", prideCompactRow("وضعیت حلقه تنظیم سوخت"));
    prideGroupSections[3] = new LinearLayout(this);
    prideGroupSections[3].setOrientation(LinearLayout.VERTICAL);
    prideGroupSections[3].setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    prideSiemensSection.addView(prideGroupSections[3]);
    content = prideGroupSections[3];
    content.addView(text("وضعیت خودرو و خروجی‌ها", 18, true));
    prideSiemensValues.put("emax_pride_mileage", prideCompactRow("مسافت پیموده‌شده"));
    prideSiemensValues.put("emax_pride_gear", prideCompactRow("وضعیت دنده"));
    prideSiemensValues.put("emax_pride_speed_disconnects", prideCompactRow("تعداد قطعی سنسور سرعت"));
    prideSiemensValues.put("emax_pride_speed_error_runtime", prideCompactRow("زمان خطای سنسور سرعت"));
    prideSiemensValues.put("emax_pride_ac_switch", prideCompactRow("کلید کولر"));
    prideSiemensValues.put("emax_pride_ac_control", prideCompactRow("کنترل کولر"));
    prideSiemensValues.put("emax_pride_ac_relay", prideCompactRow("رله کولر"));
    prideSiemensValues.put("emax_pride_fan_status", prideCompactRow("وضعیت فن خنک‌کننده"));
    content = prideSiemensSection;
    back(parametersMenu);
    showPrideGroup(-1);

    content = boschServiceSection;
    content.addView(text("Bosch زامیاد 7.9.7.1 • فریم‌ور ترکیبی V51", 17, true));
    content.addView(text("اتصال Wi-Fi فعلی برنامه و ESP-01 حفظ شده است. ابتدا اتصال TCP برقرار باشد، سپس ECU بوش را متصل کنید.", 13, false));
    button("۱ • اتصال به ECU بوش", () -> sendBoschKey('1'));
    button("۲ • خواندن کدهای خطا", () -> sendBoschKey('2'));
    boschFaultValues = field("گزارش خطاهای ECU بوش");
    button("پاک‌کردن خطاها (با تأیید)", () -> confirmBoschClear());
    button("توقف زودهنگام تست فعال (X)", () -> sendBoschKey('X'));
    button("۰ • قطع ارتباط K-Line", () -> sendBoschKey('0'));

    content.addView(text("تست مستقیم عملگرها • بدون کلید Y", 16, true));
    content.addView(text("زمان‌بندی و خاموشی خودکار در STM32 کنترل می‌شود. شرایط ایمنی هر تست را رعایت کنید.", 13, false));
    button("A • پمپ بنزین — ۶ ثانیه", () -> sendBoschKey('A'));
    button("B • انژکتور ۱ — دور آرام", () -> sendBoschKey('B'));
    button("C • انژکتور ۱ — موتور خاموش", () -> sendBoschKey('C'));
    button("D • انژکتور ۲ — دور آرام", () -> sendBoschKey('D'));
    button("E • انژکتور ۲ — موتور خاموش", () -> sendBoschKey('E'));
    button("F • انژکتور ۳ — دور آرام", () -> sendBoschKey('F'));
    button("G • انژکتور ۳ — موتور خاموش", () -> sendBoschKey('G'));
    button("H • انژکتور ۴ — دور آرام", () -> sendBoschKey('H'));
    button("I • انژکتور ۴ — موتور خاموش", () -> sendBoschKey('I'));
    button("J • گرم‌کن سنسور اکسیژن — ۶ ثانیه", () -> sendBoschKey('J'));
    button("K • استپر موتور", () -> sendBoschKey('K'));
    button("L • کویل ۱–۴", () -> sendBoschKey('L'));
    button("M • کویل ۲–۳", () -> sendBoschKey('M'));
    button("۴ • تست چراغ چک (MIL)", () -> sendBoschKey('4'));
    button("N • رله سیستم تهویه — ۶ ثانیه", () -> sendBoschKey('N'));

    button("۶ • نمایش ۱۹ پارامتر زنده بوش", () -> {
      show(boschZamyadSection);
      sendBoschKey('6');
    });
    back(ecuSelectionSection);

    content = boschZamyadSection;
    content.addView(text("پروفایل مستقل زامیاد • Bosch 7.9.7.1", 18, true));
    boschZamyadUpdated = text("آماده دریافت داده از ECU بوش", 12, false);
    content.addView(boschZamyadUpdated);
    button("خطاها و تست عملگرهای بوش", () -> show(boschServiceSection));
    String[][] boschRows = {
      {"bosch_zamyad_fault_count","شمارنده خطا (خام)"},
      {"bosch_zamyad_coolant","دمای آب"},
      {"bosch_zamyad_intake_air","دمای هوای ورودی"},
      {"bosch_zamyad_battery","ولتاژ باتری"},
      {"bosch_zamyad_tps","موقعیت دریچه گاز"},
      {"bosch_zamyad_rpm","دور موتور"},
      {"bosch_zamyad_spark_advance","آوانس جرقه"},
      {"bosch_zamyad_knock_state","وضعیت ناک"},
      {"bosch_zamyad_injection_system_state","وضعیت سیستم انژکتور"},
      {"bosch_zamyad_fuel_pump_state","وضعیت پمپ بنزین"},
      {"bosch_zamyad_injection_state","وضعیت پاشش"},
      {"bosch_zamyad_engine_load_state","وضعیت بار موتور"},
      {"bosch_zamyad_air_pressure","فشار هوا"},
      {"bosch_zamyad_injection_time","زمان پاشش"},
      {"bosch_zamyad_target_idle_rpm","دور آرام هدف"},
      {"bosch_zamyad_stepper_position","موقعیت استپر"},
      {"bosch_zamyad_map","فشار منیفولد MAP"},
      {"bosch_zamyad_coil_charge_time","زمان شارژ کویل"},
      {"bosch_zamyad_o2_voltage","ولتاژ سنسور اکسیژن"}
    };
    for (String[] row : boschRows) boschZamyadValues.put(row[0], prideCompactRow(row[1]));
    back(ecuSelectionSection);

    content = root;
    addBottomNavigation(appShell);
    applyThemeToViews(root);
    applyFontScale(root);
    show(home);
    final String restoreKey = "home";
    selectedBrand = "نیسان زامیاد";
    selectedSystemType = "بنزینی و دوگانه‌سوز";
    selectedEcu = "SB13";
    preferences.edit().putString("yasir.lastBrand", selectedBrand)
        .putString("yasir.lastEcu", selectedEcu).putString("yasir.lastScreen", "home").apply();
    if ("بوش زامیاد 7.9.7.1".equals(selectedEcu)) pendingProfileCommand = "!PROFILE:BOSCH";
    else if ("SB13".equals(selectedEcu)) pendingProfileCommand = "!PROFILE:SB13_CAN";
    else if ("زیمنس پراید".equals(selectedEcu) || "زیمنس SIM2K-34VR".equals(selectedEcu))
      pendingProfileCommand = "!PROFILE:SIEMENS";
    final LinearLayout restoreScreen = home;
    show(restoreScreen);
    final int savedY = b == null ? 0 : b.getInt("yasir.scroll", 0);
    if (savedY > 0 && scrollContainer != null)
      scrollContainer.post(() -> scrollContainer.scrollTo(0, savedY));
    if (b != null && b.getBoolean("yasir.tcpConnected", false))
      scrollContainer.post(() -> connect());
  }


  // Clean diagnostic parameter card. A row appears only after the ECU supplies a value.
  TextView prideCompactRow(String label) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(12), dp(5), dp(12), dp(5));
    row.setTag("pride-row");
    row.setBackground(panelDrawable(cardBackground(), Color.rgb(53, 91, 105), 10));
    TextView value = text("—", 18, true);
    value.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
    value.setTextColor(teal);
    row.addView(value, new LinearLayout.LayoutParams(0, dp(48), 0.42f));
    TextView name = text(label, 14, true);
    name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    name.setTextColor(Color.WHITE);
    row.addView(name, new LinearLayout.LayoutParams(0, dp(48), 0.58f));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, 0, 0, dp(7));
    content.addView(row, lp);
    row.setVisibility(View.VISIBLE);
    return value;
  }

  void setCompactParameterValue(TextView valueView, String shown) {
    if (valueView == null) return;
    View row = (View)valueView.getParent();
    String value = shown == null ? "" : shown.trim();
    if (value.isEmpty() || value.equalsIgnoreCase("null")) value = "—";
    valueView.setText(value);
    row.setVisibility(View.VISIBLE);
  }

  void refreshPrideGroupHeaders() {
    for (LinearLayout group : prideGroupSections) {
      if (group != null && group.getChildCount() > 0) group.getChildAt(0).setVisibility(View.VISIBLE);
    }
  }

  void showPrideGroup(int group) {
    refreshPrideGroupHeaders();
    for (int i = 0; i < prideGroupSections.length; i++) {
      if (prideGroupSections[i] != null)
        prideGroupSections[i].setVisibility(group < 0 || group == i ? View.VISIBLE : View.GONE);
    }
  }

  void copyPrideReport() {
    StringBuilder report = new StringBuilder("YASIR DIAG — Siemens Pride 35 parameters\n");
    if (prideSiemensUpdated != null)
      report.append(prideSiemensUpdated.getText()).append('\n');
    for (int i=0; i<PRIDE_IDS.length; i++) {
      report.append(prideFriendlyName(PRIDE_IDS[i])).append(": ");
      report.append(prideCurrentValues[i] == null ? "—" : prideCurrentValues[i]).append('\n');
    }
    android.content.ClipboardManager clipboard =
       (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
    if (clipboard != null) {
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("YASIR_Pride_Report", report.toString()));
      setStatus("گزارش ۳۵ پارامتر در حافظه کپی شد");
    }
  }


  // Save a short crash report only when Android terminates this process
  // because of an uncaught Java exception. Keep the system crash handler.
  void installCrashDiagnostics() {
    final Thread.UncaughtExceptionHandler previous =
        Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      try {
        String stack = android.util.Log.getStackTraceString(error);
        if (stack.length() > 1600) stack = stack.substring(0, 1600);
        preferences.edit()
          .putString("yasir.lastCrash", stack)
          .putLong("yasir.lastCrashTime", System.currentTimeMillis())
          .commit();
      } catch (Exception ignored) {}
      if (previous != null) previous.uncaughtException(thread, error);
    });
  }

  int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

  GradientDrawable panelDrawable(int fill, int stroke, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(Math.min(radiusDp, 2) * getResources().getDisplayMetrics().density);
    d.setStroke(Math.max(1, (int)(getResources().getDisplayMetrics().density)), stroke);
    return d;
  }

  GradientDrawable blueDiagnosticDrawable(boolean alternate) {
    GradientDrawable d = new GradientDrawable(
      GradientDrawable.Orientation.LEFT_RIGHT,
      alternate
        ? new int[]{Color.rgb(28, 65, 101), Color.rgb(34, 77, 117)}
        : new int[]{Color.rgb(18, 48, 78), Color.rgb(24, 61, 96)});
    d.setCornerRadius(0);
    d.setStroke(Math.max(1, dp(1)), Color.rgb(88, 119, 147));
    return d;
  }

  void styleBlueWorkshopPage(LinearLayout page) {
    page.setPadding(dp(8), dp(7), dp(8), dp(14));
    page.setBackgroundColor(Color.rgb(3, 20, 63));
  }

  TextView blueWorkshopHeader(String title, String subtitle) {
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setGravity(Gravity.CENTER);
    header.setPadding(dp(8), dp(5), dp(8), dp(6));
    GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
      new int[]{Color.rgb(15, 42, 70), Color.rgb(25, 67, 105)});
    bg.setCornerRadius(dp(2));
    bg.setStroke(dp(1), Color.rgb(210, 235, 255));
    header.setBackground(bg);
    TextView titleView = text(title, 16, true);
    titleView.setTextColor(Color.WHITE);
    titleView.setGravity(Gravity.CENTER);
    TextView subtitleView = text(subtitle, 11, false);
    subtitleView.setTextColor(Color.rgb(185, 224, 255));
    subtitleView.setGravity(Gravity.CENTER);
    header.addView(titleView);
    header.addView(subtitleView);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, 0, 0, dp(6));
    content.addView(header, p);
    return titleView;
  }

  void blueWorkshopRow(LinearLayout parent, String title, String icon, Runnable action) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(52));
    row.setPadding(dp(7), dp(5), dp(5), dp(5));
    row.setBackground(blueDiagnosticDrawable(parent.getChildCount() % 2 == 0));
    TextView label = text(title, 15, true);
    label.setTextColor(Color.WHITE);
    label.setGravity(Gravity.CENTER);
    row.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
    TextView symbol = text(icon, 20, true);
    symbol.setTextColor(Color.rgb(8, 43, 100));
    symbol.setGravity(Gravity.CENTER);
    symbol.setBackground(panelDrawable(Color.rgb(226, 240, 255), Color.WHITE, 2));
    row.addView(symbol, new LinearLayout.LayoutParams(dp(42), dp(40)));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(0, 0, 0, dp(3));
    parent.addView(row, p);
    row.setOnClickListener(v -> action.run());
  }

  LinearLayout sectionTitle(String title, String subtitle, String icon) {
    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.VERTICAL);
    wrap.setPadding(2, 14, 2, 7);
    TextView heading = text(title, 19, true);
    heading.setTextColor(Color.rgb(235, 244, 252));
    wrap.addView(heading);
    TextView sub = text(subtitle, 11, false);
    sub.setTextColor(Color.rgb(162, 187, 210));
    wrap.addView(sub);
    return wrap;
  }

  void addIndustrialCompanyCard(android.widget.GridLayout grid, String name, int imageIndex, String code) {
    int[] accents = new int[]{Color.rgb(0, 190, 244), Color.rgb(255, 119, 24), Color.rgb(238, 35, 61), Color.rgb(0, 190, 222), Color.rgb(177, 91, 244), Color.rgb(0, 193, 139), Color.rgb(255, 185, 0), Color.rgb(117, 145, 148)};
    int accent = accents[imageIndex % accents.length];
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER);
    card.setPadding(dp(3), dp(5), dp(3), dp(4));
    card.setTag("industrial-company");
    card.setBackground(panelDrawable(Color.rgb(5, 20, 22), accent, 13));

    TextView badge = text(code, 12, true);
    badge.setTextColor(accent);
    badge.setGravity(Gravity.CENTER);
    badge.setPadding(0, 0, 0, dp(1));
    card.addView(badge, new LinearLayout.LayoutParams(-1, dp(22)));

    TextView label = text(name, 11, true);
    label.setTextColor(Color.rgb(239, 248, 243));
    label.setGravity(Gravity.CENTER);
    label.setMaxLines(2);
    label.setMinHeight(dp(30));
    label.setPadding(0, 0, 0, dp(2));
    card.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));

    ImageView car = new ImageView(this);
    car.setTag(Integer.valueOf(imageIndex));
    Bitmap bitmap = vehicleBitmap(imageIndex);
    if (bitmap != null) car.setImageBitmap(bitmap);
    car.setScaleType(ImageView.ScaleType.CENTER_CROP);
    car.setBackground(panelDrawable(Color.rgb(2, 13, 13), Color.rgb(22, 52, 43), 8));
    car.setClipToOutline(true);
    card.addView(car, new LinearLayout.LayoutParams(-1, dp(68)));
    loadVehiclePhoto(car, imageIndex);

    TextView enter = text("›", 18, true);
    enter.setTextColor(accent);
    enter.setGravity(Gravity.CENTER);
    enter.setPadding(0, 0, 0, 0);
    card.addView(enter, new LinearLayout.LayoutParams(-1, dp(17)));

    card.setOnClickListener(v -> openEcuMenu(name));
    android.widget.GridLayout.LayoutParams p = new android.widget.GridLayout.LayoutParams();
    p.width = 0;
    p.height = dp(151);
    p.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
    p.setMargins(dp(3), dp(3), dp(3), dp(3));
    grid.addView(card, p);
  }

  void addQuickCard(android.widget.GridLayout grid, String title, String subtitle, String icon, int accent, Runnable action) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.HORIZONTAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(10, 11, 10, 11);
    card.setTag("quick-card");
    card.setBackground(panelDrawable(Color.rgb(7, 26, 31), accent, 14));
    TextView glyph = text(icon, 28, true);
    glyph.setTextColor(accent);
    glyph.setGravity(Gravity.CENTER);
    card.addView(glyph, new LinearLayout.LayoutParams(48, 52));
    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    TextView name = text(title, 14, true);
    name.setTextColor(Color.rgb(239, 248, 243));
    labels.addView(name);
    TextView desc = text(subtitle, 10, false);
    desc.setTextColor(Color.rgb(154, 183, 175));
    labels.addView(desc);
    card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    card.setOnClickListener(v -> action.run());
    android.widget.GridLayout.LayoutParams p = new android.widget.GridLayout.LayoutParams();
    p.width = 0;
    p.height = -2;
    p.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
    p.setMargins(4, 4, 4, 4);
    grid.addView(card, p);
  }

  void addUtilityCard(android.widget.GridLayout grid, String title, String subtitle, String icon, Runnable action) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER);
    card.setPadding(5, 9, 5, 9);
    card.setTag("utility-card");
    card.setBackground(panelDrawable(Color.rgb(10, 27, 29), Color.rgb(42, 76, 73), 12));
    TextView glyph = text(icon, 25, true);
    glyph.setTextColor(Color.rgb(105, 233, 115));
    glyph.setGravity(Gravity.CENTER);
    card.addView(glyph);
    TextView name = text(title, 12, true);
    name.setTextColor(Color.rgb(237, 246, 239));
    name.setGravity(Gravity.CENTER);
    card.addView(name);
    TextView desc = text(subtitle, 10, false);
    desc.setTextColor(Color.rgb(143, 176, 161));
    desc.setGravity(Gravity.CENTER);
    card.addView(desc);
    card.setOnClickListener(v -> action.run());
    android.widget.GridLayout.LayoutParams p = new android.widget.GridLayout.LayoutParams();
    p.width = 0;
    p.height = -2;
    p.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
    p.setMargins(3, 3, 3, 3);
    grid.addView(card, p);
  }

  void addBottomNavigation(LinearLayout shell) {
    LinearLayout nav = new LinearLayout(this);
    nav.setOrientation(LinearLayout.HORIZONTAL);
    nav.setGravity(Gravity.CENTER);
    nav.setPadding(5, 7, 5, 7);
    nav.setBackground(panelDrawable(Color.rgb(10, 25, 41), Color.rgb(74, 105, 132), 0));
    String[] labels = {"خانه", "خودروها", "دیاگ", "گزارش‌ها", "تنظیمات"};
    String[] icons = {"⌂", "▣", "⌕", "▤", "⚙"};
    Runnable[] actions = new Runnable[] {
      () -> show(home),
      () -> showManualEcuPicker(),
      () -> show(home),
      () -> show(dtcSection),
      () -> show(settingsSection)
    };
    for (int i=0; i<labels.length; i++) {
      final int index = i;
      LinearLayout item = new LinearLayout(this);
      item.setOrientation(LinearLayout.VERTICAL);
      item.setGravity(Gravity.CENTER);
      item.setPadding(2, 4, 2, 4);
      TextView glyph = text(icons[i], 22, true);
      glyph.setGravity(Gravity.CENTER);
      glyph.setTextColor(i == 0 ? Color.rgb(120, 190, 230) : Color.rgb(167, 187, 205));
      glyph.setPadding(2, 0, 2, 0);
      item.addView(glyph);
      TextView label = text(labels[i], 10, true);
      label.setGravity(Gravity.CENTER);
      label.setTextColor(i == 0 ? Color.rgb(153, 211, 243) : Color.rgb(177, 194, 209));
      label.setPadding(0, 0, 0, 0);
      item.addView(label);
      item.setOnClickListener(v -> actions[index].run());
      nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(8, 5, 8, 7);
    shell.addView(nav, p);
  }

  void addCompanyCard(LinearLayout parent, String name, int imageIndex) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(8, 7, 12, 7);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor((parent.getChildCount() % 2 == 0) ? Color.WHITE : Color.rgb(234,236,240));
    bg.setCornerRadius(10);
    bg.setStroke(1, Color.rgb(31,44,112));
    row.setTag("company-card");
    row.setBackground(bg);
    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 104);
    rowParams.setMargins(0, 3, 0, 3);

    ImageView car = new ImageView(this);
    Bitmap bitmap = vehicleBitmap(imageIndex);
    if (bitmap != null) car.setImageBitmap(bitmap);
    car.setScaleType(ImageView.ScaleType.CENTER_CROP);
    row.addView(car, new LinearLayout.LayoutParams(124, 88));

    TextView label = text(name, 20, true);
    label.setGravity(Gravity.CENTER);
    label.setTextColor(Color.rgb(25,28,35));
    row.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
    row.setOnClickListener(v -> openEcuMenu(name));
    parent.addView(row, rowParams);
  }

  void loadVehiclePhoto(ImageView target, int index) {
    Bitmap cached;
    synchronized (vehiclePhotoCache) {
      cached = vehiclePhotoCache.get(index);
      if (cached != null) {
        target.setImageBitmap(cached);
        return;
      }
      if (Boolean.TRUE.equals(vehiclePhotoLoading.get(index))) return;
      vehiclePhotoLoading.put(index, Boolean.TRUE);
    }
    final String[] urls = new String[] {
      "https://images.unsplash.com/photo-1674110958136-40fd83adc9e3?auto=format&fit=crop&fm=jpg&ixid=NHwxMjA3fDA%3D&ixlib=rb-4.1.0&q=85&w=900",
      "https://images.unsplash.com/photo-1760713170685-b67abc3be5ad?auto=format&fit=crop&fm=jpg&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D&ixlib=rb-4.1.0&q=85&w=900",
      "https://images.unsplash.com/photo-1764962904631-98f6c441a2b8?auto=format&fit=crop&fm=jpg&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D&ixlib=rb-4.1.0&q=85&w=900",
      "https://images.unsplash.com/photo-1564063812-442d960674c5?auto=format&fit=crop&fm=jpg&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D&ixlib=rb-4.1.0&q=85&w=900",
      "https://images.unsplash.com/photo-1750830331454-86df5c088ca7?auto=format&fit=crop&fm=jpg&ixid=NHwxMjA3fDA%3D&ixlib=rb-4.1.0&q=85&w=900",
      "https://images.unsplash.com/photo-1704476944918-c1258561ebb9?auto=format&fit=crop&fm=jpg&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D&ixlib=rb-4.1.0&q=85&w=900",
      "https://images.unsplash.com/photo-1698237551041-682150030b13?auto=format&fit=crop&fm=jpg&ixid=M3wxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8fA%3D%3D&ixlib=rb-4.1.0&q=85&w=900",
      "https://images.unsplash.com/photo-1669676867270-9d7afd8cd656?auto=format&fit=crop&fm=jpg&ixid=NHwxMjA3fDA%3D&ixlib=rb-4.1.0&q=85&w=900"
    };
    new Thread(() -> {
      HttpURLConnection connection = null;
      Bitmap photo = null;
      File photoFile = new File(getCacheDir(), "yasir_vehicle_" + index + ".jpg");
      try {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        if (photoFile.isFile() && photoFile.length() > 4096) {
          photo = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
        }
        if (photo == null) {
          connection = (HttpURLConnection)new URL(urls[Math.max(0, Math.min(urls.length - 1, index))]).openConnection();
          connection.setConnectTimeout(10000);
          connection.setReadTimeout(12000);
          connection.setInstanceFollowRedirects(true);
          connection.setRequestProperty("User-Agent", "YasirDiagAndroid/1.5");
          connection.connect();
          File tempFile = new File(getCacheDir(), "yasir_vehicle_" + index + ".tmp");
          try (InputStream input = connection.getInputStream();
               FileOutputStream output = new FileOutputStream(tempFile)) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) output.write(chunk, 0, count);
          }
          if (tempFile.length() > 4096 && tempFile.renameTo(photoFile)) {
            photo = BitmapFactory.decodeFile(photoFile.getAbsolutePath(), options);
          }
          if (tempFile.exists()) tempFile.delete();
        }
      } catch (Exception ignored) {
      } finally {
        if (connection != null) connection.disconnect();
      }
      final Bitmap result = photo;
      synchronized (vehiclePhotoCache) {
        vehiclePhotoLoading.remove(index);
        if (result != null) vehiclePhotoCache.put(index, result);
      }
      if (result != null) ui.post(() -> {
        Object tag = target.getTag();
        if (tag instanceof Integer && ((Integer)tag).intValue() == index) target.setImageBitmap(result);
      });
    }, "vehicle-photo-" + index).start();
  }

  Bitmap vehicleBitmap(int index) {
    BitmapRegionDecoder decoder = null;
    try {
      decoder = BitmapRegionDecoder.newInstance(getResources().openRawResource(R.drawable.cars_grid), false);
      int cellW = decoder.getWidth() / 3;
      int cellH = decoder.getHeight() / 3;
      int col = Math.max(0, Math.min(2, index % 3));
      int row = Math.max(0, Math.min(2, index / 3));
      int left = col * cellW + 1, top = row * cellH + 1;
      int right = Math.min(decoder.getWidth(), (col + 1) * cellW - 1);
      int bottom = Math.min(decoder.getHeight(), (row + 1) * cellH - 1);
      return decoder.decodeRegion(new Rect(left, top, right, bottom), new BitmapFactory.Options());
    } catch (Exception ignored) {
      return null;
    } finally {
      if (decoder != null) decoder.recycle();
    }
  }

  void addBrandDirectoryRow(LinearLayout parent, String name, String code, String subtitle) {
    // V-MAX-inspired flat scanner row with a right-hand typographic brand tile.
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(66));
    row.setPadding(dp(4), dp(4), dp(4), dp(4));
    row.setBackground(blueDiagnosticDrawable(parent.getChildCount() % 2 != 0));

    TextView badge = text(code, code.length() > 7 ? 8 : code.length() > 5 ? 10 : 12, true);
    badge.setGravity(Gravity.CENTER);
    badge.setTextColor(Color.rgb(29, 57, 87));
    badge.setMaxLines(2);
    badge.setBackground(panelDrawable(Color.rgb(225, 235, 228), Color.WHITE, 2));
    row.addView(badge, new LinearLayout.LayoutParams(dp(72), -1));

    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.setGravity(Gravity.CENTER);
    labels.setPadding(dp(7), 0, dp(7), 0);
    TextView title = text(name, 17, true);
    title.setGravity(Gravity.CENTER);
    title.setTextColor(Color.WHITE);
    title.setMaxLines(1);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    labels.addView(title, new LinearLayout.LayoutParams(-1, -2));
    TextView hint = text(subtitle, 9, false);
    hint.setGravity(Gravity.CENTER);
    hint.setTextColor(Color.rgb(207, 231, 249));
    hint.setMaxLines(1);
    hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
    labels.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    row.addView(labels, new LinearLayout.LayoutParams(0, -1, 1));

    TextView arrow = text("›", 22, true);
    arrow.setGravity(Gravity.CENTER);
    arrow.setTextColor(Color.WHITE);
    row.addView(arrow, new LinearLayout.LayoutParams(dp(24), -1));

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(66));
    lp.setMargins(0, 0, 0, dp(7));
    parent.addView(row, lp);
    row.setOnClickListener(v -> openEcuMenu(name));
  }

  void showSystemCatalog() {
    systemSelectionSection.removeAllViews();
    systemSelectionSection.setPadding(dp(12), dp(8), dp(12), dp(10));
    TextView heading = text(selectedBrand + "  |  انتخاب سیستم یا یونیت", 20, true);
    heading.setTextColor(Color.rgb(235, 246, 255));
    systemSelectionSection.addView(heading);
    TextView caption = text("ابتدا نوع سامانه را انتخاب کنید؛ سپس مدل ECU یا یونیت مربوط نمایش داده می‌شود.", 13, false);
    caption.setTextColor(Color.rgb(172, 199, 212));
    systemSelectionSection.addView(caption);
    addCatalogRow(systemSelectionSection, "جستجوی خودکار ECU", "یافتن پاسخ ECU روی CAN", "CAN", () -> startAutomaticCanScan());
    TextView manualTitle = text("انتخاب دستی سیستم", 16, true);
    manualTitle.setTextColor(Color.rgb(94, 221, 208));
    manualTitle.setPadding(0, dp(10), 0, dp(4));
    systemSelectionSection.addView(manualTitle);
    String[] mainSystems = {"بنزینی و دوگانه‌سوز", "گازسوز (CNG/LPG)", "دیزل", "ترمز ضدقفل (ABS)", "بدنه و تجهیزات رفاهی", "ضدسرقت و ایموبیلایزر"};
    String[] mainIcons = {"ECU", "CNG", "D", "ABS", "BCM", "IMMO"};
    for (int i=0; i<mainSystems.length; i++) {
      final String systemName = mainSystems[i];
      addCatalogRow(systemSelectionSection, systemName, "موتور و سامانه‌های اصلی", mainIcons[i], () -> showEcuCatalog(systemName));
    }
    TextView sideTitle = text("یونیت‌های جانبی", 17, true);
    sideTitle.setTextColor(Color.rgb(94, 221, 208));
    sideTitle.setPadding(0, dp(12), 0, dp(4));
    systemSelectionSection.addView(sideTitle);
    String[] sideUnits = {"کیسه هوا (SRS)", "گیربکس اتوماتیک (TCU)", "صفحه کیلومتر و پشت‌آمپر", "فرمان برقی (EPS)", "تهویه مطبوع", "بدنه و BCM/BSI"};
    String[] sideIcons = {"SRS", "TCU", "CL", "EPS", "HVAC", "BCM"};
    for (int i=0; i<sideUnits.length; i++) {
      final String unit = sideUnits[i];
      addCatalogRow(systemSelectionSection, unit, "یونیت جانبی خودرو", sideIcons[i], () -> showEcuCatalog(unit));
    }
    addCatalogRow(systemSelectionSection, "بازگشت به برندها", "انتخاب سازنده دیگر", "‹", () -> show(home));
    show(systemSelectionSection);
  }

  void addCatalogRow(LinearLayout parent, String title, String subtitle, String icon, Runnable action) {
    // Matching square-corner scanner rows for systems, ECU models and side units.
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(62));
    row.setPadding(dp(4), dp(4), dp(4), dp(4));
    row.setBackground(blueDiagnosticDrawable(parent.getChildCount() % 2 != 0));

    TextView symbol = text(icon, icon.length() > 4 ? 10 : 12, true);
    symbol.setGravity(Gravity.CENTER);
    symbol.setTextColor(Color.rgb(29, 57, 87));
    symbol.setMaxLines(2);
    symbol.setBackground(panelDrawable(Color.rgb(225, 235, 228), Color.WHITE, 2));
    row.addView(symbol, new LinearLayout.LayoutParams(dp(68), -1));

    LinearLayout names = new LinearLayout(this);
    names.setOrientation(LinearLayout.VERTICAL);
    names.setGravity(Gravity.CENTER);
    names.setPadding(dp(7), 0, dp(7), 0);
    TextView name = text(title, 15, true);
    name.setGravity(Gravity.CENTER);
    name.setTextColor(Color.WHITE);
    name.setMaxLines(1);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    names.addView(name, new LinearLayout.LayoutParams(-1, -2));
    TextView detail = text(subtitle, 10, false);
    detail.setGravity(Gravity.CENTER);
    detail.setTextColor(Color.rgb(207, 231, 249));
    detail.setMaxLines(1);
    detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
    names.addView(detail, new LinearLayout.LayoutParams(-1, -2));
    row.addView(names, new LinearLayout.LayoutParams(0, -1, 1));

    TextView next = text("›", 22, true);
    next.setGravity(Gravity.CENTER);
    next.setTextColor(Color.WHITE);
    row.addView(next, new LinearLayout.LayoutParams(dp(24), -1));

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(62));
    lp.setMargins(0, 0, 0, dp(6));
    parent.addView(row, lp);
    row.setOnClickListener(v -> action.run());
  }

  String[] ecuCatalogFor(String brand, String system) {
    if (system.startsWith("بنزینی")) {
      String[] sharedProfiles = {
        "زیمنس پراید • پروفایل فعال",
        "بوش زامیاد 7.9.7.1 • پروفایل فعال",
        "عظام SB13 بنزینی یورو ۵ • پروفایل فعال CAN"
      };
      if ("زامیاد".equals(brand))
        return new String[]{sharedProfiles[2], sharedProfiles[1], sharedProfiles[0], "بوش M7.9.7.1", "زیمنس زامیاد بنزینی"};
      if ("سایپا".equals(brand))
        return new String[]{sharedProfiles[0], sharedProfiles[1], sharedProfiles[2], "ساژم پراید SL96", "زیمنس دوگانه‌سوز"};
      return new String[]{sharedProfiles[0], sharedProfiles[1], sharedProfiles[2],
        "Bosch ME7.4.4 / ME7.4.5", "Siemens SIM2K-34VR", "Sagem S2000 / SL96"};
    }
    if (system.contains("ABS")) return new String[]{"Bosch ABS", "Continental / Teves ABS", "Mando ABS", "ATE ABS"};
    if (system.contains("ایموبیلایزر") || system.contains("ضدسرقت")) return new String[]{"یونیت ایموبیلایزر", "IMMO / کلید و آنتن", "BCM دارای ایموبیلایزر"};
    if (system.contains("کیسه هوا")) return new String[]{"یونیت ایربگ SRS", "Bosch / Continental SRS", "Autoliv SRS"};
    if (system.contains("گیربکس")) return new String[]{"TCU گیربکس اتوماتیک", "Jatco TCM", "ZF / Bosch TCM"};
    if (system.contains("کیلومتر")) return new String[]{"صفحه کیلومتر", "پشت‌آمپر و یونیت نمایشگر", "یونیت شبکه بدنه"};
    if (system.contains("فرمان")) return new String[]{"یونیت فرمان برقی EPS", "ستون فرمان برقی"};
    if (system.contains("تهویه")) return new String[]{"یونیت تهویه مطبوع", "Climate Control"};
    if (system.contains("بدنه") || system.contains("BCM")) return new String[]{"BCM / BSI", "یونیت بدنه و رفاهی", "یونیت مرکزی قفل و شیشه‌ها"};
    if (system.contains("گازسوز")) return new String[]{"ECU گاز CNG", "ECU دوگانه‌سوز", "زیمنس گازسوز • مدل باید شناسایی شود", "واحد کنترل LPG"};
    if (system.equals("دیزل")) return new String[]{"Bosch EDC", "Delphi Diesel", "Denso Diesel", "Siemens Diesel"};
    return new String[]{"Bosch ME7.4.4 / ME7.4.5", "Siemens SIM2K-34VR", "Sagem S2000 / SL96", "ECU بنزینی دوگانه‌سوز"};
  }

  void showEcuCatalog(String system) {
    selectedSystemType = system;
    ecuSelectionSection.removeAllViews();
    ecuSelectionSection.setPadding(dp(12), dp(8), dp(12), dp(10));
    selectedBrandLabel = text(selectedBrand + "  |  " + system, 19, true);
    selectedBrandLabel.setTextColor(Color.rgb(235, 246, 255));
    ecuSelectionSection.addView(selectedBrandLabel);
    TextView note = text("خانواده‌های ECU برای انتخاب دستی • اتصال فقط پس از تطبیق مدل و پروتکل ECU انجام می‌شود.", 12, false);
    note.setTextColor(Color.rgb(163, 196, 207));
    ecuSelectionSection.addView(note);
    for (String model : ecuCatalogFor(selectedBrand, system)) {
      boolean active = model.contains("پروفایل فعال") || ("زامیاد".equals(selectedBrand) && model.startsWith("عظام SB13 بنزینی")) || ("زامیاد".equals(selectedBrand) && model.startsWith("بوش زامیاد 7.9.7.1"));
      final String selectedModel = model;
      addCatalogRow(ecuSelectionSection, model, active ? "پروفایل ارتباطی موجود • تطبیق با مدل ECU" : "کاتالوگ • پروفایل اختصاصی نیازمند تأیید", active ? "●" : "○", () -> {
        if (selectedModel.startsWith("عظام SB13 بنزینی")) chooseEcu("SB13");
        else if (selectedModel.startsWith("عظام SB13 دوگانه")) chooseEcu("SB13 دوگانه‌سوز یورو ۵");
        else if (selectedModel.startsWith("بوش زامیاد 7.9.7.1")) chooseEcu("بوش زامیاد 7.9.7.1");
        else if (selectedModel.startsWith("زیمنس پراید")) chooseEcu("زیمنس پراید");
        else chooseEcu(selectedModel);
      });
    }
    addCatalogRow(ecuSelectionSection, "بازگشت به سیستم‌ها", "تغییر ECU یا یونیت", "‹", () -> show(systemSelectionSection));
    show(ecuSelectionSection);
  }

  void openEcuMenu(String brand) {
    selectedBrand = brand;
    selectedSystemType = "";
    showSystemCatalog();
  }

  void showManualEcuPicker() {
    show(home);
    setStatus("برند خودرو را انتخاب کنید و سپس ECU را از فهرست دستی برگزینید");
  }

  void selectManualEcu(String ecu) {
    selectedBrand = "نیسان زامیاد";
    selectedEcu = ecu;
    pendingProfileCommand = "!PROFILE:SB13_CAN";
    preferences.edit().putString("yasir.lastBrand", selectedBrand)
        .putString("yasir.lastEcu", selectedEcu).putString("yasir.lastScreen", "home").apply();
    if (tcpLive) {
      String command = pendingProfileCommand;
      pendingProfileCommand = "";
      send(command);
      ui.postDelayed(() -> {
        if (tcpLive) send("!1");
      }, 350);
    } else {
      scanAfterTcpConnect = true;
      connect();
    }
    setStatus("انتخاب دستی ECU ثبت شد؛ در حال بررسی ارتباط CAN");
    show(home);
  }

  void appendEcuInfoLine(StringBuilder target, String label, String value) {
    if (value == null || value.trim().isEmpty()) return;
    target.append("\n").append(label).append(": ").append(value.trim());
  }

  void updateAutoSearchResult(String message) {
    if (autoSearchStatus != null) ui.post(() -> autoSearchStatus.setText(message));
  }

  void startAutomaticCanScan() {
    ecuActionsShownForCurrentConnection = false;
    if (selectedBrand == null || selectedBrand.trim().isEmpty()) selectedBrand = "زامیاد";
    selectedEcu = "شناسایی خودکار ECU";
    preferences.edit().putString("yasir.lastBrand", selectedBrand)
        .putString("yasir.lastEcu", selectedEcu).putString("yasir.lastScreen", "autoSearch").apply();
    autoDetectionActive = true;
    autoCanConnected = false;
    autoDetectionStage = 1;
    autoDetectionToken++;
    if (autoSearchStatus != null)
      autoSearchStatus.setText("جستجوی ECU شروع شد\n۱) بوش روی K-Line  •  ۲) زیمنس روی K-Line  •  ۳) OBD-II روی CAN");
    diagnosticSelectionLabel.setText("خودرو: " + selectedBrand + "   |   ECU: در حال شناسایی خودکار");
    show(autoSearchSection);
    setStatus("جستجوی خودکار پروفایل‌های فعال ECU آغاز شد");
    if (!tcpLive || socket == null || socket.isClosed()) {
      pendingProfileCommand = "!PROFILE:BOSCH";
      scanAfterTcpConnect = true;
      connect();
      return;
    }
    beginAutomaticDetectionStage(1);
  }

  void beginAutomaticDetectionStage(int stage) {
    if (!autoDetectionActive || !tcpLive || socket == null || socket.isClosed()) return;
    autoDetectionStage = stage;
    final int token = ++autoDetectionToken;
    final String profile = stage == 1 ? "BOSCH" : stage == 2 ? "SIEMENS" : "SB13_CAN";
    final String label = stage == 1 ? "بوش زامیاد روی K-Line" :
        stage == 2 ? "زیمنس پراید روی K-Line" : "OBD-II عمومی روی CAN";
    if (autoSearchStatus != null) autoSearchStatus.setText("در حال بررسی " + label + " ...");
    setStatus("شناسایی خودکار: " + label);
    send("!PROFILE:" + profile);
    ui.postDelayed(() -> {
      if (!autoDetectionActive || token != autoDetectionToken || !tcpLive) return;
      send("!1");
      armAutomaticDetectionTimeout(stage, token);
    }, 350);
  }

  void armAutomaticDetectionTimeout(final int stage, final int token) {
    long waitMs = stage == 1 ? 4300 : stage == 2 ? 5200 : 10500;
    ui.postDelayed(() -> {
      if (!autoDetectionActive || token != autoDetectionToken || autoDetectionStage != stage) return;
      if (stage < 3) beginAutomaticDetectionStage(stage + 1);
      else if (autoCanConnected)
        finishAutomaticDetection("ارتباط OBD-II روی CAN برقرار شد، اما شناسهٔ ECU برنگشت. برای خواندن Mode 09، firmware روی STM32 باید به V52 به‌روزرسانی شود.");
      else finishAutomaticDetection("پاسخی از پروفایل‌های فعال بوش/زیمنس یا OBD-II CAN دریافت نشد. ECUهای دیگر به پروتکل و شناسهٔ اختصاصی خود نیاز دارند.");
    }, waitMs);
  }

  void finishAutomaticDetection(String result) {
    autoDetectionActive = false;
    autoDetectionStage = 0;
    autoDetectionToken++;
    if (autoSearchStatus != null) autoSearchStatus.setText(result);
    boolean identified = result != null &&
        (result.contains("شناسایی شد") || result.contains("سازگار است") ||
         result.contains("پاسخ داده با پروفایل") || result.contains("ECU روی CAN پاسخ داد") ||
         result.contains("ارتباط OBD-II روی CAN برقرار شد") || result.contains("ECU روی CAN پاسخ می‌دهد"));
    if (identified) showEcuActionsMenu();
    setStatus(identified ? "ECU شناسایی شد؛ منوی عیب‌یابی آماده است" : "جستجوی خودکار پایان یافت");
  }

  void showEcuActionsMenu() {
    if (ecuActionsShownForCurrentConnection) return;
    ecuActionsShownForCurrentConnection = true;
    if (diagnosticSelectionLabel != null)
      diagnosticSelectionLabel.setText("خودرو: " + selectedBrand + "   |   ECU: " +
          (selectedEcu == null || selectedEcu.trim().isEmpty() ? "ECU شناسایی‌شده" : selectedEcu));
    show(diagnosticMenu);
  }

  boolean isBoschSelected() {
    return selectedEcu != null && selectedEcu.startsWith("بوش زامیاد");
  }

  boolean isSiemensSelected() {
    return selectedEcu != null && selectedEcu.startsWith("زیمنس");
  }

  boolean isCanSelected() {
    return "SB13".equals(selectedEcu) || (selectedEcu != null && selectedEcu.contains("CAN"));
  }

  void openIdentifiedEcuFaults() {
    show(dtcSection);
    if (isBoschSelected()) sendBoschKey('2');
    else send("!2");
  }

  void confirmIdentifiedEcuClear() {
    if (isBoschSelected()) confirmBoschClear();
    else confirmClear();
  }

  void openIdentifiedEcuParameters() {
    if (isBoschSelected()) {
      show(boschZamyadSection);
      sendBoschKey('6');
    } else if (isSiemensSelected()) {
      show(prideSiemensSection);
    } else if (isCanSelected()) {
      dynamicReturnTarget = diagnosticMenu;
      show(dynamicParametersSection);
      prepareSb13Live();
    } else {
      show(parametersMenu);
    }
  }

  void openIdentifiedEcuActuators() {
    if (isBoschSelected()) show(boschServiceSection);
    else if (isSiemensSelected()) show(actuatorSection);
    else new AlertDialog.Builder(this)
        .setTitle("عملکردها")
        .setMessage("برای این ECU در فریم‌ور فعلی تست عملگر تأییدشده‌ای تعریف نشده است.")
        .setPositiveButton("باشه", null).show();
  }

  void requestProfileCommand(String command) {
    pendingProfileCommand = command;
    if (tcpLive && socket != null && !socket.isClosed()) {
      String sendNow = pendingProfileCommand;
      pendingProfileCommand = "";
      send(sendNow);
    } else {
      setStatus("پروفایل ذخیره شد؛ پس از اتصال به E-MAX ارسال می‌شود");
    }
  }

  void startSelectedEcu(String profile, String liveCommand, LinearLayout target) {
    ecuActionsShownForCurrentConnection = false;
    preferences.edit().putString("yasir.lastBrand", selectedBrand)
        .putString("yasir.lastEcu", selectedEcu).putString("yasir.lastScreen", "ecuSelection").apply();
    diagnosticSelectionLabel.setText("خودرو: " + selectedBrand + "   |   ECU: " + selectedEcu);
    show(target);
    if (!tcpLive || socket == null || socket.isClosed()) {
      pendingProfileCommand = profile;
      pendingPostConnectCommand = liveCommand == null ? "" : liveCommand;
      scanAfterTcpConnect = true;
      setStatus("در حال اتصال به ECU انتخاب‌شده");
      connect();
      return;
    }
    sendSelectedEcuSequence(profile, liveCommand);
  }

  void sendSelectedEcuSequence(final String profile, final String liveCommand) {
    new Thread(() -> {
      try {
        synchronized (this) {
          if (out == null || socket == null || socket.isClosed()) throw new IOException("اتصال TCP برقرار نیست");
          out.write((profile + "\n").getBytes("US-ASCII"));
          out.flush();
        }
        Thread.sleep(220);
        synchronized (this) {
          if (out == null || socket == null || socket.isClosed()) return;
          out.write("!1\n".getBytes("US-ASCII"));
          out.flush();
        }
        if (liveCommand != null && !liveCommand.isEmpty()) {
          Thread.sleep(1200);
          synchronized (this) {
            if (out == null || socket == null || socket.isClosed()) return;
            out.write((liveCommand + "\n").getBytes("US-ASCII"));
            out.flush();
          }
        }
        setStatus("فرمان اتصال پروفایل " + selectedEcu + " ارسال شد");
      } catch (Exception e) {
        setStatus("اتصال به ECU ناموفق: " + e.getMessage());
      }
    }, "yasir-selected-ecu-connect").start();
  }

  void chooseEcu(String ecu) {
    String requested = ecu == null ? "" : ecu.trim();
    if (requested.equals("SB13") || requested.startsWith("عظام SB13 بنزینی")) {
      selectedEcu = "SB13";
      seedSb13ParameterRows();
      startSelectedEcu("!PROFILE:SB13_CAN", "!6", dynamicParametersSection);
      return;
    }
    if (requested.startsWith("بوش زامیاد 7.9.7.1")) {
      selectedEcu = "بوش زامیاد 7.9.7.1";
      startSelectedEcu("!PROFILE:BOSCH", "!6", boschZamyadSection);
      return;
    }
    if (requested.startsWith("زیمنس پراید")) {
      selectedEcu = "زیمنس پراید";
      startSelectedEcu("!PROFILE:SIEMENS", "", prideSiemensSection);
      return;
    }
    if (requested.startsWith("زیمنس SIM2K-34VR")) {
      selectedEcu = "زیمنس SIM2K-34VR";
      startSelectedEcu("!PROFILE:SIEMENS", "", diagnosticMenu);
      return;
    }
    selectedEcu = ecu;
    new AlertDialog.Builder(this)
      .setTitle(ecu)
      .setMessage("این ECU در کاتالوگ وجود دارد، اما هنوز پروفایل ارتباطی تأییدشده ندارد. برای اتصال باید پروتکل همین مدل ECU به برنامه افزوده شود.")
      .setPositiveButton("متوجه شدم", (d,w) -> show(ecuSelectionSection))
      .show();
  }

  LinearLayout section(String title) {
    LinearLayout section = new LinearLayout(this);
    section.setOrientation(LinearLayout.VERTICAL);
    section.setVisibility(View.GONE);
    root.addView(section);
    if (title != null && !title.trim().isEmpty()) section.addView(text(title,22,true));
    return section;
  }

  String screenKey(LinearLayout screen) {
    if (screen == prideSiemensSection) return "pride";
    if (screen == boschZamyadSection) return "boschZamyad";
    if (screen == boschServiceSection) return "boschService";
    if (screen == sb13CanSection) return "sb13Can";
    if (screen == dashboardSection) return "dashboard";
    if (screen == diagnosticMenu) return "diagnostic";
    if (screen == parametersMenu) return "parameters";
    if (screen == dynamicParametersSection) return "dynamic";
    if (screen == dtcSection) return "dtc";
    if (screen == ecuSelectionSection) return "ecuSelection";
    if (autoSearchSection != null && screen == autoSearchSection) return "autoSearch";
    if (systemSelectionSection != null && screen == systemSelectionSection) return "systemSelection";
    if (screen == speedSection) return "speed";
    if (screen == emissionsSection) return "emissions";
    if (screen == airSection) return "air";
    if (screen == fuelSection) return "fuel";
    if (screen == ignitionSection) return "ignition";
    if (screen == actuatorSection) return "actuators";
    if (screen == settingsSection) return "settings";
    if (screen == updateSection) return "update";
    if (screen == helpSection) return "help";
    return "home";
  }

  LinearLayout screenFromKey(String key) {
    if ("pride".equals(key)) return prideSiemensSection;
    if ("boschZamyad".equals(key)) return boschZamyadSection;
    if ("boschService".equals(key)) return boschServiceSection;
    if ("dashboard".equals(key)) return dashboardSection;
    if ("diagnostic".equals(key)) return diagnosticMenu;
    if ("parameters".equals(key)) return parametersMenu;
    if ("dynamic".equals(key)) return dynamicParametersSection;
    if ("dtc".equals(key)) return dtcSection;
    if ("ecuSelection".equals(key)) return ecuSelectionSection;
    if ("autoSearch".equals(key)) return autoSearchSection;
    if ("systemSelection".equals(key)) return systemSelectionSection;
    if ("speed".equals(key)) return speedSection;
    if ("emissions".equals(key)) return emissionsSection;
    if ("air".equals(key)) return airSection;
    if ("fuel".equals(key)) return fuelSection;
    if ("ignition".equals(key)) return ignitionSection;
    if ("actuators".equals(key)) return actuatorSection;
    if ("settings".equals(key)) return settingsSection;
    if ("update".equals(key)) return updateSection;
    if ("help".equals(key)) return helpSection;
    return home;
  }

  void show(LinearLayout screen) {
    if (screen == null || currentScreen == screen) return;
    if (scrollContainer != null && currentScreen != null) {
      screenScrollPositions.put(screenKey(currentScreen), scrollContainer.getScrollY());
      currentScreen.setVisibility(View.GONE);
    }
    currentScreen = screen;
    activeScreenKey = screenKey(screen);
    boolean compactBlueWorkshop = screen == sb13CanSection || screen == dynamicParametersSection || screen == autoSearchSection;
    if (appBrandHeader != null)
      appBrandHeader.setVisibility(compactBlueWorkshop ? View.GONE : View.VISIBLE);
    screen.setVisibility(View.VISIBLE);
    preferences.edit().putString("yasir.lastScreen", activeScreenKey)
        .putString("yasir.lastBrand", selectedBrand)
        .putString("yasir.lastEcu", selectedEcu).apply();
    if (scrollContainer != null) {
      final int position = screenScrollPositions.containsKey(activeScreenKey) ?
          screenScrollPositions.get(activeScreenKey) : 0;
      scrollContainer.post(() -> {
        if (currentScreen == screen) scrollContainer.scrollTo(0, position);
      });
    }
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    state.putString("yasir.screen", activeScreenKey);
    state.putString("yasir.brand", selectedBrand);
    state.putString("yasir.system", selectedSystemType);
    state.putString("yasir.ecu", selectedEcu);
    state.putInt("yasir.scroll", scrollContainer == null ? 0 : scrollContainer.getScrollY());
    state.putBoolean("yasir.tcpConnected", tcpLive);
    super.onSaveInstanceState(state);
  }

  boolean isIdentifiedEcuSubPage() {
    if (!ecuActionsShownForCurrentConnection) return false;
    return currentScreen == dtcSection || currentScreen == dashboardSection ||
        currentScreen == parametersMenu || currentScreen == speedSection ||
        currentScreen == emissionsSection || currentScreen == ignitionSection ||
        currentScreen == airSection || currentScreen == fuelSection ||
        currentScreen == actuatorSection || currentScreen == resetSection ||
        currentScreen == chartSection || currentScreen == dynamicParametersSection ||
        currentScreen == prideSiemensSection || currentScreen == boschZamyadSection ||
        currentScreen == boschServiceSection || currentScreen == sb13CanSection ||
        currentScreen == ecuSection;
  }

  void back(LinearLayout target) {
    button("بازگشت", () -> show(isIdentifiedEcuSubPage() ? diagnosticMenu : target));
  }

  void confirmBoschClear() {
    new AlertDialog.Builder(this)
      .setTitle("پاک‌کردن خطاهای Bosch زامیاد")
      .setMessage("موتور خاموش و سوئیچ باز باشد. پس از تأیید، فرمان ۳ به فریم‌ور V50 ارسال می‌شود.")
      .setNegativeButton("انصراف", null)
      .setPositiveButton("تأیید پاک کردن", (d, w) -> sendBoschKey('3'))
      .show();
  }

  void confirmActuator(String name, char command, boolean highRisk) {
    String warning = "فقط با موتور خاموش، خودرو ثابت و محیط ایمن اجرا کنید. فرمان به ECU ارسال می‌شود و فریم‌ور V42 باید توقف خودکار ۲٫۵ ثانیه‌ای داشته باشد.";
    if (highRisk) {
      warning += "\n\nهشدار جدی: تست کویل یا انژکتور می‌تواند خطر جرقه یا پاشش سوخت ایجاد کند. فقط با روش تعمیراتی و تجهیزات ایمن انجام دهید؛ نه با موتور روشن.";
    }
    new AlertDialog.Builder(this)
      .setTitle("تأیید تست " + name)
      .setMessage(warning)
      .setNegativeButton("انصراف", null)
      .setPositiveButton("آماده‌سازی و اجرای کوتاه", (d, w) -> sendActuatorTest(command))
      .show();
  }

  // V42 firmware requires an explicit arm (!X) before a one-shot actuator ID.
  // Send both lines from one worker in order; do not spawn competing send threads.
  void sendActuatorTest(char command) {
    new Thread(() -> {
      try {
        synchronized (this) {
          if (out == null || socket == null || socket.isClosed()) {
            setStatus("اتصال TCP برقرار نیست؛ ابتدا به E-MAX وصل شوید");
            return;
          }
          out.write("!X\n".getBytes("US-ASCII"));
          out.flush();
          Thread.sleep(250);
          out.write(("!" + Character.toUpperCase(command) + "\n").getBytes("US-ASCII"));
          out.flush();
        }
        setStatus("فرمان آماده‌سازی و تست " + command + " ارسال شد؛ در انتظار پاسخ ECU");
      } catch (Exception e) {
        setStatus("خطای ارسال تست عملگر: " + e.toString());
        closeSocket();
      }
    }, "yasir-actuator-test").start();
  }

  void confirmClear() {
    new AlertDialog.Builder(this)
      .setTitle("تأیید پاک کردن خطا")
      .setMessage("آیا از پاک کردن خطاهای ECU مطمئن هستید؟")
      .setNegativeButton("انصراف",null)
      .setPositiveButton("پاک کن",(d,w) -> send("!3")).show();
  }

  @Override public void onBackPressed() {
    if (isIdentifiedEcuSubPage()) show(diagnosticMenu);
    else if(currentScreen == home) super.onBackPressed();
    else if(currentScreen == ecuSelectionSection) show(systemSelectionSection);
    else if(currentScreen == autoSearchSection) show(systemSelectionSection);
    else if(currentScreen == systemSelectionSection) show(home);
    else if(currentScreen == diagnosticMenu) show(home);
    else if(currentScreen == boschServiceSection || currentScreen == sb13CanSection) show(ecuSelectionSection);
    else if(currentScreen == boschZamyadSection) show(boschServiceSection);
    else if(currentScreen == prideSiemensSection) show(parametersMenu);
    else if(currentScreen == speedSection || currentScreen == emissionsSection || currentScreen == ignitionSection || currentScreen == airSection || currentScreen == fuelSection) show(parametersMenu);
    else show(home);
  }

  TextView text(String value,int size,boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(navy);
    t.setPadding(12,10,12,10);
    if(bold)t.setTypeface(null,Typeface.BOLD);
    t.setGravity(Gravity.RIGHT);
    return t;
  }

  TextView field(String label) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(15,12,15,12);
    card.setTag("value-card");
    card.setBackgroundColor(Color.WHITE);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
    p.setMargins(0,7,0,7);
    content.addView(card,p);
    card.addView(text(label,15,false));
    TextView v = text("—",22,true);
    v.setTextColor(teal);
    card.addView(v);
    return v;
  }

  TextView tableRow(String label) {
    LinearLayout line = new LinearLayout(this);
    line.setOrientation(LinearLayout.HORIZONTAL);
    line.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.setPadding(12,18,12,18);
    line.setTag("table-row");
    line.setBackgroundColor((content.getChildCount()%2==0) ? Color.rgb(224,233,244) : Color.WHITE);
    TextView value = text("—",17,true);
    value.setGravity(Gravity.CENTER);
    value.setTextColor(teal);
    line.addView(value, new LinearLayout.LayoutParams(0,-2,1));
    TextView name = text(label,15,false);
    name.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    line.addView(name, new LinearLayout.LayoutParams(0,-2,2));
    content.addView(line,new LinearLayout.LayoutParams(-1,-2));
    return value;
  }

  void updateTable(JSONArray data,TextView[] values,String unit) {
    for(int i=0;i<values.length;i++) {
      values[i].setText(data != null && i<data.length() && !data.isNull(i) ? data.optString(i) + " " + unit : "—");
    }
  }

  String standardCode(String raw) {
    if(raw == null) return "";
    String s = raw.trim().toUpperCase(java.util.Locale.US);
    if(s.matches("[PCBU][0-3][0-9A-F]{3}")) return s;
    String[] parts = s.split("[^0-9A-F]+");
    if(parts.length >= 2 && parts[0].length()==2 && parts[1].length()==2) {
      try {
        int a = Integer.parseInt(parts[0],16);
        int b = Integer.parseInt(parts[1],16);
        char type = "PCBU".charAt((a>>6)&3);
        int second = (a>>4)&3;
        return "" + type + second + Integer.toHexString(a&15).toUpperCase(java.util.Locale.US) + String.format(java.util.Locale.US,"%02X",b);
      } catch(Exception ignored) {}
    }
    return s;
  }

  // Bosch Zamyad reports three raw bytes. The first two bytes match the
  // standard P-code encoding for known captures; keep the full raw record too
  // and label this mapping as generic until verified against a Zamyad reference.
  String boschStandardCode(String raw) {
    if (raw == null) return "";
    String s = raw.trim().toUpperCase(java.util.Locale.US);
    if (s.matches("[0-9A-F]{6}"))
      return standardCode(s.substring(0, 2) + " " + s.substring(2, 4));
    return s;
  }

  String boschDtcDescription(String code) {
    switch (code) {
      case "P0105": return "مدار سنسور فشار منیفولد MAP • توضیح عمومی OBD-II";
      case "P0107": return "مدار سنسور فشار منیفولد MAP: سیگنال ولتاژ پایین • توضیح عمومی OBD-II";
      case "P0117": return "مدار سنسور دمای مایع خنک‌کننده: سیگنال ولتاژ پایین • توضیح عمومی OBD-II";
      case "P0118": return "مدار سنسور دمای مایع خنک‌کننده: سیگنال ولتاژ بالا • توضیح عمومی OBD-II";
      case "P0300": return "احتراق ناقص تصادفی یا در چند سیلندر • توضیح عمومی OBD-II";
      case "P0304": return "احتراق ناقص در سیلندر شمارهٔ ۴ • توضیح عمومی OBD-II";
      case "P0509": return "مدار کنترل هوای دور آرام: سیگنال بالا • توضیح عمومی OBD-II";
      default: return "برای این کد، توضیح عمومی در برنامه ثبت نشده است.";
    }
  }

  void button(String title,Runnable r) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setTextSize(15);
    b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    b.setBackground(panelDrawable(Color.rgb(31, 83, 126), Color.rgb(91, 132, 165), 0));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
    p.setMargins(0, dp(4), 0, dp(4));
    content.addView(b, p);
    b.setOnClickListener(v -> r.run());
  }

  void configurePalette() {
    if (themeMode == 1) {
      navy = Color.rgb(8, 20, 35);
      teal = Color.rgb(54, 132, 190);
    } else if (themeMode == 2) {
      navy = Color.rgb(21, 69, 58);
      teal = Color.rgb(0, 126, 96);
    } else if (themeMode == 0) {
      navy = Color.rgb(13, 26, 44);
      teal = Color.rgb(0, 135, 135);
    } else {
      navy = Color.rgb(3, 17, 15);
      teal = Color.rgb(105, 233, 115);
    }
  }

  int pageBackground() {
    if (themeMode == 1) return Color.rgb(14, 29, 47);
    if (themeMode == 2) return Color.rgb(235, 246, 240);
    if (themeMode == 0) return Color.rgb(241, 246, 250);
    return Color.rgb(2, 12, 12);
  }

  int cardBackground() {
    if (themeMode == 1) return Color.rgb(31, 49, 69);
    if (themeMode == 2 || themeMode == 0) return themeMode == 2 ? Color.rgb(250, 255, 251) : Color.WHITE;
    return Color.rgb(5, 20, 22);
  }

  int rowBackground(boolean alternate) {
    if (themeMode == 1) return alternate ? Color.rgb(37, 57, 77) : Color.rgb(27, 44, 63);
    if (themeMode == 2) return alternate ? Color.rgb(224, 240, 231) : Color.WHITE;
    return alternate ? Color.rgb(234, 236, 240) : Color.WHITE;
  }

  int foregroundColor() {
    return (themeMode == 1 || themeMode == 3) ? Color.rgb(239, 247, 252) : Color.rgb(25, 34, 46);
  }

  void setThemeMode(int mode) {
    themeMode = mode;
    preferences.edit().putInt("theme_mode", mode).apply();
    configurePalette();
    applyThemeToViews(root);
    setStatus("رنگ‌بندی ذخیره شد");
  }

  void setUiScale(float scale) {
    uiScale = scale;
    preferences.edit().putFloat("ui_scale", scale).apply();
    applyFontScale(root);
    setStatus("اندازه نوشته‌ها ذخیره شد");
  }

  void applyFontScale(View view) {
    if (view instanceof TextView) {
      TextView tv = (TextView)view;
      Object saved = tv.getTag();
      if (!(saved instanceof Float)) {
        float original = tv.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
        tv.setTag(Float.valueOf(original));
        saved = Float.valueOf(original);
      }
      tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, ((Float)saved).floatValue() * uiScale);
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup)view;
      for (int i=0; i<group.getChildCount(); i++) applyFontScale(group.getChildAt(i));
    }
  }

  void applyThemeToViews(View view) {
    root.setBackgroundColor(pageBackground());
    getWindow().setStatusBarColor(navy);
    if (view instanceof TextView) {
      TextView tv = (TextView)view;
      tv.setTextColor(foregroundColor());
      if (tv == status) tv.setTextColor(teal);
      if (tv == updateInfo) tv.setTextColor(teal);
      if (view instanceof Button) {
        ((Button)view).setBackgroundTintList(android.content.res.ColorStateList.valueOf(
          themeMode == 1 ? Color.rgb(35, 112, 117) : (themeMode == 2 ? Color.rgb(190, 225, 205) : Color.rgb(218, 225, 231))));
      }
    }
    if (view instanceof LinearLayout) {
      LinearLayout layout = (LinearLayout)view;
      Object tag = layout.getTag();
      if ("company-card".equals(tag) || "value-card".equals(tag)) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBackground());
        bg.setCornerRadius(10);
        bg.setStroke(1, themeMode == 1 ? Color.rgb(67, 125, 144) : Color.rgb(31, 44, 112));
        layout.setBackground(bg);
      } else if (tag instanceof String && ((String)tag).startsWith("dynamic-row:")) {
        int rowRank = 0;
        try { rowRank = Integer.parseInt(((String)tag).substring("dynamic-row:".length())); }
        catch (Exception ignored) {}
        layout.setBackground(blueDiagnosticDrawable((rowRank & 1) == 0));
      } else if ("table-row".equals(tag) || "pride-row".equals(tag)) {
        boolean alternate = (layout.getChildCount() % 2 == 0);
        layout.setBackgroundColor(rowBackground(alternate));
      }
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup)view;
      for (int i=0; i<group.getChildCount(); i++) applyThemeToViews(group.getChildAt(i));
    }
  }

  void refreshUpdateInfo() {
    if (updateInfo == null) return;
    updateInfo.setText("نسخه نصب‌شده: 2.6.2\nبرای بررسی نسخه رسمی، دکمه «بررسی نسخه منتشرشده» را بزنید.");
  }

  void checkForUpdates() {
    if (updateInfo != null) updateInfo.setText("در حال بررسی نسخه رسمی...");
    new Thread(() -> {
      HttpURLConnection connection = null;
      try {
        URL url = new URL("https://api.github.com/repos/tanzimsarkori/fluffy-octo-garbanzo/releases/latest");
        connection = (HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        int code = connection.getResponseCode();
        if (code == 200) {
          ByteArrayOutputStream buffer = new ByteArrayOutputStream();
          try (InputStream in = connection.getInputStream()) {
            byte[] data = new byte[2048]; int n;
            while ((n = in.read(data)) > 0) buffer.write(data, 0, n);
          }
          JSONObject release = new JSONObject(new String(buffer.toByteArray(), "UTF-8"));
          String tag = release.optString("tag_name", "نسخه نامشخص");
          String urlText = release.optString("html_url", "https://github.com/tanzimsarkori/fluffy-octo-garbanzo/releases");
          final String message = "آخرین نسخه منتشرشده: " + tag + "\nبرای دریافت و نصب، صفحه رسمی نسخه را باز کنید.";
          ui.post(() -> { if (updateInfo != null) updateInfo.setText(message); });
          setStatus("اطلاعات آخرین نسخه دریافت شد: " + tag);
        } else if (code == 404) {
          ui.post(() -> { if (updateInfo != null) updateInfo.setText("هنوز نسخه‌ای در بخش Releases منتشر نشده است. از صفحه رسمی پروژه، نسخه APK تأییدشده را دریافت کنید."); });
        } else {
          ui.post(() -> { if (updateInfo != null) updateInfo.setText("بررسی آنلاین انجام نشد (HTTP " + code + "). اتصال اینترنت را بررسی یا صفحه رسمی نسخه‌ها را باز کنید."); });
        }
      } catch (Exception e) {
        ui.post(() -> { if (updateInfo != null) updateInfo.setText("اتصال برای بررسی نسخه برقرار نشد. می‌توانید صفحه رسمی نسخه‌ها را دستی باز کنید."); });
      } finally {
        if (connection != null) connection.disconnect();
      }
    }, "yasir-update-check").start();
  }

  void openReleasePage() {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tanzimsarkori/fluffy-octo-garbanzo/releases")));
    } catch (Exception e) {
      setStatus("مرورگر برای باز کردن صفحه نسخه‌ها در دسترس نیست");
    }
  }

  // Fixed-width, fixed-height badge, independent of incoming status text.
  // Transient TCP reconnects get 2 seconds before displaying a real disconnection.
  void displayTcpBadge(boolean connected) {
    if (exiting || status == null) return;
    String badge = connected ? "● TCP\nمتصل" : "● TCP\nقطع";
    if (!badge.contentEquals(status.getText())) status.setText(badge);
    status.setTextColor(connected ? Color.rgb(83, 224, 183) :
        Color.rgb(238, 181, 117));
  }

  void updateTcpBadge(boolean connected, boolean delayDisconnect) {
    ui.post(() -> {
      if (exiting || status == null) return;
      ui.removeCallbacks(showRealTcpLoss);
      if (connected) displayTcpBadge(true);
      else if (delayDisconnect) ui.postDelayed(showRealTcpLoss, 2000);
      else displayTcpBadge(false);
    });
  }

  // Status messages are still preserved for diagnosis, but they must never
  // resize the TCP banner, which caused the entire home screen to jump.
  void setStatus(String message) {
    if (exiting || message == null) return;
    if (message.equals(lastUiStatus)) return;
    lastUiStatus = message;
    android.util.Log.d("YasirDiag", message);
    ui.post(() -> {
      if (exiting) return;
      if (lastDiagnosticStatus != null &&
          !message.contentEquals(lastDiagnosticStatus.getText()))
        lastDiagnosticStatus.setText(message);
    });
  }

  // Bosch V51 commands are raw single ASCII keys; Siemens keeps the existing ! command protocol.
  void sendBoschKey(char key) {
    if (key >= 'a' && key <= 'n') key = (char)(key - 'a' + 'A');
    final char command = key;
    if ("012346ABCDEFGHIJKLMNX?".indexOf(command) < 0) {
      setStatus("فرمان نامعتبر برای Bosch 7.9.7.1");
      return;
    }
    new Thread(() -> {
      try {
        synchronized (this) {
          if (out == null || socket == null || socket.isClosed()) {
            setStatus("اتصال TCP برقرار نیست؛ ابتدا اتصال Wi-Fi دستگاه را برقرار کنید");
            return;
          }
          out.write(("!" + String.valueOf(command) + "\n").getBytes("US-ASCII"));
          out.flush();
        }
        setStatus("فرمان Bosch ارسال شد: " + command);
      } catch (Exception e) {
        setStatus("ارسال فرمان Bosch ناموفق: " + e.toString());
        closeSocket();
      }
    }, "yasir-bosch-v51-command").start();
  }

  void send(String command) {
    // Never block the UI thread on a slow WiFi/TCP socket.
    new Thread(() -> {
      try {
        synchronized (this) {
          if (out == null || socket == null || socket.isClosed()) {
            setStatus("اتصال TCP برقرار نیست؛ ابتدا اتصال به دستگاه را بزنید");
            return;
          }
          // STM32 V34 expects an exclamation-mark-prefixed ASCII command.
          byte[] bytes = (command + "\n").getBytes("US-ASCII");
          out.write(bytes);
          out.flush();
        }
        setStatus("فرمان " + command + " ارسال شد؛ منتظر پاسخ ECU...");
      } catch (Exception e) {
        setStatus("خطای ارسال " + command + ": " + e.toString());
        closeSocket();
      }
    }, "yasir-command").start();
  }


  // Wi-Fi without Internet must still be used for 192.168.4.1,
  // even when Android prefers cellular for regular Internet requests.
  Socket createEspSocketOnWifi() throws IOException {
    ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
    if (cm == null) throw new IOException("سرویس شبکه اندروید در دسترس نیست");
    for (Network n : cm.getAllNetworks()) {
      NetworkCapabilities caps = cm.getNetworkCapabilities(n);
      if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return n.getSocketFactory().createSocket();
      }
    }
    throw new IOException("گوشی به Wi-Fi E-MAX وصل نیست");
  }

  void connect() {
    disconnect();
    final int epoch = ++connectionEpoch;
    running = true;
    setStatus("در حال اتصال از مسیر Wi-Fi به E-MAX ...");
    new Thread(() -> {
      int attempts = 0;
      while (running && connectionEpoch == epoch) {
        Socket s = null;
        try {
          attempts++;
          s = createEspSocketOnWifi();
          s.setKeepAlive(true);
          s.setTcpNoDelay(true);
          s.connect(new InetSocketAddress(host, port), 4500);
          if (!running || connectionEpoch != epoch) {
            try { s.close(); } catch (Exception ignored) {}
            break;
          }
          synchronized (this) {
            if (!running || connectionEpoch != epoch) {
              try { s.close(); } catch (Exception ignored) {}
              break;
            }
            socket = s;
            out = s.getOutputStream();
            tcpLive = true;
          }
          updateTcpBadge(true, false);
          setStatus("TCP به E-MAX برقرار شد؛ اتصال ECU را بزنید");
          if (!pendingProfileCommand.isEmpty()) {
            final String profileCmd = pendingProfileCommand;
            pendingProfileCommand = "";
            ui.postDelayed(() -> {
              if (running && connectionEpoch == epoch) send(profileCmd);
            }, 180);
          }
          if (scanAfterTcpConnect) {
            ui.postDelayed(() -> {
              if (running && connectionEpoch == epoch) {
                send("!1");
                if (autoDetectionActive) {
                  setStatus("ارتباط دستگاه برقرار شد؛ مرحلهٔ اول شناسایی ECU شروع شد");
                  armAutomaticDetectionTimeout(autoDetectionStage, autoDetectionToken);
                } else {
                  String followUp = pendingPostConnectCommand;
                  pendingPostConnectCommand = "";
                  if (followUp != null && !followUp.isEmpty())
                    ui.postDelayed(() -> { if (tcpLive) send(followUp); }, 1200);
                  setStatus("ارتباط دستگاه برقرار شد؛ اتصال ECU انتخاب‌شده آغاز شد");
                }
              }
              scanAfterTcpConnect = false;
            }, 520);
          }

          BufferedReader reader =
              new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
          String line;
          while (running && connectionEpoch == epoch &&
                 (line = reader.readLine()) != null) {
            final String received = line;
            if (received.length() > 12000) {
              setStatus("پیام بیش از حد طولانی از ESP دریافت شد");
              continue;
            }
            final int packetEpoch = epoch;
            ui.post(() -> {
              if (running && connectionEpoch == packetEpoch) parse(received);
            });
          }
          if (running && connectionEpoch == epoch)
            setStatus("TCP از سوی ESP بسته شد؛ در حال اتصال مجدد...");
        } catch (Exception e) {
          if (running && connectionEpoch == epoch) {
            String detail = e.getMessage();
            if (detail == null || detail.trim().isEmpty())
              detail = e.getClass().getSimpleName();
            setStatus("اتصال TCP قطع یا ناموفق؛ تلاش مجدد (" + detail + ")");
          }
        } finally {
          synchronized (this) {
            if (socket == s) {
              try { if (socket != null) socket.close(); } catch (Exception ignored) {}
              socket = null;
              out = null;
              tcpLive = false;
              if (running && connectionEpoch == epoch)
                updateTcpBadge(false, true);
            } else {
              try { if (s != null) s.close(); } catch (Exception ignored) {}
            }
          }
        }
        if (running && connectionEpoch == epoch) {
          try { Thread.sleep(1600); }
          catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }, "yasir-wifi-tcp-" + epoch).start();
  }

  String friendlyParameterName(String key) {
    if ("rpm".equals(key)) return "دور موتور";
    if ("coolant".equals(key)) return "دمای آب";
    if ("intake".equals(key)) return "دمای هوای ورودی";
    if ("battery".equals(key)) return "ولتاژ باتری";
    if ("map".equals(key)) return "فشار MAP (نیازمند تأیید)";
    if ("mapRaw".equals(key)) return "بایت خام MAP نامزد";
    if ("tps".equals(key)) return "موقعیت دریچه گاز (در انتظار فرمول)";
    if ("tpsRaw".equals(key)) return "بایت خام TPS (در انتظار شناسایی)";
    if ("connected".equals(key)) return "وضعیت اتصال ECU";
    return key;
  }

  void seedSb13ParameterRows() {
    if (dynamicParameterList == null || dynamicParameterList.getChildCount() > 0) return;
    String[][] rows = {
      {"sb13_can_42","ولتاژ باتری / تغذیه ECU","V"},
      {"sb13_can_0C","دور موتور","rpm"},
      {"sb13_can_0D","سرعت خودرو","km/h"},
      {"sb13_can_05","دمای آب موتور","°C"},
      {"sb13_can_0F","دمای هوای ورودی","°C"},
      {"sb13_can_0B","فشار منیفولد MAP","kPa"},
      {"sb13_can_04","بار محاسبه‌شده موتور","%"},
      {"sb13_can_11","موقعیت دریچه گاز A","%"},
      {"sb13_can_45","موقعیت نسبی دریچه گاز","%"},
      {"sb13_can_47","موقعیت دریچه گاز B","%"},
      {"sb13_can_48","موقعیت دریچه گاز C","%"},
      {"sb13_can_49","موقعیت پدال گاز D","%"},
      {"sb13_can_4A","موقعیت پدال گاز E","%"},
      {"sb13_can_4B","موقعیت پدال گاز F","%"},
      {"sb13_can_4C","موقعیت درخواستی دریچه برقی","%"},
      {"sb13_can_0E","آوانس جرقه","°"},
      {"sb13_can_10","دبی هوای ورودی MAF","g/s"},
      {"sb13_can_0A","فشار سوخت","kPa"},
      {"sb13_can_06","تصحیح کوتاه‌مدت سوخت بانک ۱","%"},
      {"sb13_can_07","تصحیح بلندمدت سوخت بانک ۱","%"},
      {"sb13_can_08","تصحیح کوتاه‌مدت سوخت بانک ۲","%"},
      {"sb13_can_09","تصحیح بلندمدت سوخت بانک ۲","%"},
      {"sb13_can_2F","سطح سوخت باک","%"},
      {"sb13_can_2C","فرمان EGR","%"},
      {"sb13_can_2E","فرمان شیر بخار بنزین","%"},
      {"sb13_can_01_mil","چراغ اخطار موتور MIL",""},
      {"sb13_can_01_count","تعداد خطاهای موتور ECU","کد"},
      {"sb13_can_03_fuel1","وضعیت سامانه سوخت ۱",""},
      {"sb13_can_03_fuel2","وضعیت سامانه سوخت ۲",""},
      {"sb13_can_1C","استاندارد عیب‌یابی ECU",""},
      {"sb13_can_1F","زمان کارکرد موتور","s"},
      {"sb13_can_21","مسافت با چراغ چک روشن","km"},
      {"sb13_can_31","مسافت از پاک‌کردن خطاها","km"},
      {"sb13_can_43","بار مطلق موتور","%"},
      {"sb13_can_44","نسبت سوخت و هوای درخواستی ECU","λ"},
      {"sb13_can_5C","دمای روغن موتور","°C"},
      {"sb13_can_61","گشتاور درخواستی راننده","%"},
      {"sb13_can_62","گشتاور واقعی موتور","%"},
      {"sb13_can_63","گشتاور مرجع موتور","N·m"},
      {"sb13_can_50","حداکثر دبی هوای قابل‌اندازه‌گیری","g/s"},
      {"sb13_can_51","نوع سوخت گزارش‌شده ECU",""}
    };
    for (String[] row : rows)
      upsertDynamicParameter(row[0], row[1], "—", row[2], "استاندارد OBD-II");
  }

  void prepareSb13Live() {
    seedSb13ParameterRows();
    send("!6");
  }

  void upsertDynamicParameter(String id, String name, String value, String unit, String statusText) {
    if (id == null || id.trim().isEmpty()) id = name;
    if (id == null || id.trim().isEmpty()) return;
    TextView val = dynamicParameterValues.get(id);
    if (val == null) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(8), dp(10), dp(8));
      row.setTag("dynamic-row");
      row.setBackground(panelDrawable(cardBackground(), Color.rgb(53, 91, 105), 10));
      LinearLayout labelColumn = new LinearLayout(this);
      labelColumn.setOrientation(LinearLayout.VERTICAL);
      labelColumn.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
      labelColumn.setPadding(dp(4), 0, dp(5), 0);
      TextView label = text(name == null ? id : name, 14, true);
      label.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
      label.setPadding(dp(3), dp(1), dp(3), dp(1));
      label.setTextColor(Color.WHITE);
      TextView state = text(statusText == null || statusText.trim().isEmpty() ? "در انتظار داده" : statusText, 10, false);
      state.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
      state.setPadding(dp(3), dp(1), dp(3), dp(1));
      state.setTextColor(Color.rgb(187, 222, 255));
      labelColumn.addView(label);
      labelColumn.addView(state);
      row.addView(labelColumn, new LinearLayout.LayoutParams(0, -2, 1.55f));
      val = text("—", 19, true);
      val.setTextColor(Color.WHITE);
      val.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
      val.setPadding(dp(4), 0, dp(4), 0);
      row.addView(val, new LinearLayout.LayoutParams(0, -2, 1.0f));
      View accent = new View(this);
      accent.setBackground(panelDrawable(teal, teal, 2));
      LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(4), dp(34));
      accentParams.setMargins(dp(5), 0, dp(7), 0);
      row.addView(accent, accentParams);
      int rank = dynamicParameterRank(id);
      int insertion = 0;
      while (insertion < dynamicParameterList.getChildCount()) {
        Object existingTag = dynamicParameterList.getChildAt(insertion).getTag();
        int existingRank = Integer.MAX_VALUE;
        if (existingTag instanceof String && ((String)existingTag).startsWith("dynamic-row:")) {
          try { existingRank = Integer.parseInt(((String)existingTag).substring(12)); }
          catch (Exception ignored) {}
        }
        if (existingRank > rank) break;
        insertion++;
      }
      row.setTag("dynamic-row:" + rank);
      LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
      rowParams.setMargins(0, 0, 0, dp(7));
      dynamicParameterList.addView(row, insertion, rowParams);
      dynamicParameterValues.put(id, val);
      dynamicParameterNames.put(id, label);
      dynamicParameterStatuses.put(id, state);
      dynamicParameterRanks.put(id, rank);
    } else {
      TextView label = dynamicParameterNames.get(id);
      if (label != null && name != null && !name.trim().isEmpty()) label.setText(name);
      TextView state = dynamicParameterStatuses.get(id);
      if (state != null && statusText != null && !statusText.trim().isEmpty()) state.setText(statusText);
    }
    String shown = value == null || value.equals("null") || value.trim().isEmpty() ? "—" : value.trim();
    if (!shown.equals("—") && unit != null && !unit.trim().isEmpty()) shown += " " + unit.trim();
    if (!shown.contentEquals(val.getText())) val.setText(shown);
    val.setVisibility(View.VISIBLE);
    if (val.getParent() instanceof View) ((View)val.getParent()).setVisibility(View.VISIBLE);
    TextView stateView = dynamicParameterStatuses.get(id);
    if (stateView != null) {
      boolean failed = statusText != null && (statusText.contains("پاسخی") || statusText.contains("عدم پاسخ") || statusText.contains("خطا"));
      stateView.setTextColor(failed ? Color.rgb(255, 205, 130) : Color.rgb(187, 222, 255));
      val.setTextColor((value == null || "—".equals(value)) ? Color.rgb(255, 214, 145) : Color.WHITE);
    }
  }

  int dynamicParameterRank(String id) {
    if (id == null || !id.startsWith("sb13_can_") || id.length() < 2) return 1000;
    String suffix = id.substring(id.length() - 2).toUpperCase(java.util.Locale.US);
    if ("42".equals(suffix)) return 5; // ECU / battery voltage first
    if ("0C".equals(suffix)) return 10; // RPM
    if ("11".equals(suffix)) return 20; // throttle A
    if ("45".equals(suffix)) return 21; // relative throttle
    if ("47".equals(suffix)) return 22;
    if ("48".equals(suffix)) return 23;
    if ("49".equals(suffix)) return 24;
    if ("4A".equals(suffix)) return 25;
    if ("4B".equals(suffix)) return 26;
    if ("4C".equals(suffix)) return 27; // commanded throttle
    if ("05".equals(suffix)) return 30; // coolant
    if ("0F".equals(suffix)) return 31; // intake air
    if ("0D".equals(suffix)) return 40; // speed
    if ("0B".equals(suffix)) return 50; // MAP
    try { return 100 + Integer.parseInt(suffix, 16); }
    catch (Exception ignored) { return 1000; }
  }


  String prideFriendlyName(String id) {
    switch (id) {
      case "emax_pride_battery": return "ولتاژ باتری";
      case "emax_pride_coolant": return "دمای آب موتور";
      case "emax_pride_air_temp": return "دمای هوای ورودی";
      case "emax_pride_tps_voltage": return "ولتاژ سنسور دریچه گاز";
      case "emax_pride_tps_angle": return "زاویه دریچه گاز";
      case "emax_pride_map": return "فشار منیفولد MAP";
      case "emax_pride_rpm": return "دور موتور";
      case "emax_pride_engine_load": return "بار موتور";
      case "emax_pride_vehicle_speed": return "سرعت خودرو";
      case "emax_pride_o2_voltage_1": return "ولتاژ سنسور اکسیژن ۱";
      case "emax_pride_o2_voltage_2": return "ولتاژ سنسور اکسیژن ۲";
      case "emax_pride_o2_correction": return "تصحیح سنسور اکسیژن";
      case "emax_pride_injection_time": return "زمان پاشش موتور";
      case "emax_pride_injector_1": return "زمان پاشش انژکتور ۱";
      case "emax_pride_injector_2": return "زمان پاشش انژکتور ۲";
      case "emax_pride_injector_3": return "زمان پاشش انژکتور ۳";
      case "emax_pride_injector_4": return "زمان پاشش انژکتور ۴";
      case "emax_pride_injector_average": return "میانگین زمان پاشش";
      case "emax_pride_stepper_actual": return "موقعیت واقعی استپر";
      case "emax_pride_stepper_target": return "موقعیت هدف استپر";
      case "emax_pride_canister_ocr": return "فرمان شیر کنیستر";
      case "emax_pride_canister_load": return "بار کنیستر";
      case "emax_pride_spark_1": return "آوانس جرقه سیلندر ۱";
      case "emax_pride_spark_2": return "آوانس جرقه سیلندر ۲";
      case "emax_pride_spark_3": return "آوانس جرقه سیلندر ۳";
      case "emax_pride_spark_4": return "آوانس جرقه سیلندر ۴";
      case "emax_pride_mixture_status": return "وضعیت حلقه تنظیم سوخت";
      case "emax_pride_mileage": return "مسافت پیموده‌شده";
      case "emax_pride_gear": return "وضعیت دنده";
      case "emax_pride_speed_disconnects": return "تعداد قطعی سنسور سرعت";
      case "emax_pride_speed_error_runtime": return "زمان خطای سنسور سرعت";
      case "emax_pride_ac_switch": return "کلید کولر";
      case "emax_pride_ac_control": return "کنترل کولر";
      case "emax_pride_ac_relay": return "رله کولر";
      case "emax_pride_fan_status": return "وضعیت فن خنک‌کننده";
      default: return id;
    }
  }

  void updateDynamicParameters(JSONObject j) {
    JSONArray params = j.optJSONArray("parameters");
    if (params != null) {
      for (int i = 0; i < params.length(); i++) {
        JSONObject p = params.optJSONObject(i);
        if (p == null) continue;
        String id = p.optString("id", p.optString("key", "param_" + i));
        String name = p.optString("name", prideFriendlyName(id));
        Object rawValue = p.opt("value");
        String value = rawValue == null || rawValue == JSONObject.NULL ? "—" : String.valueOf(rawValue);
        String unit = p.optString("unit", "");
        String state = p.optString("status", "");
        upsertDynamicParameter(id, name, value, unit, state);
        if (id.startsWith("emax_pride_")) {
          TextView prideValue = prideSiemensValues.get(id);
          if (prideValue != null) {
            setCompactParameterValue(prideValue, value + (unit.isEmpty() ? "" : " " + unit));
            prideValue.setTextColor(teal);
            refreshPrideGroupHeaders();
            if (prideSiemensUpdated != null)
              prideSiemensUpdated.setText("زیمنس پراید • آخرین دریافت: " +
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
          }
        }
        if (id.startsWith("bosch_zamyad_")) {
          TextView boschValue = boschZamyadValues.get(id);
          if (boschValue != null) {
            String shown = value;
            if (!unit.isEmpty()) shown += " " + unit;
            if (!state.isEmpty()) shown += " • " + state;
            setCompactParameterValue(boschValue, shown);
            boschValue.setTextColor(teal);
          }
          if (boschZamyadUpdated != null)
            boschZamyadUpdated.setText("بوش زامیاد 7.9.7.1 • آخرین دریافت: " +
              new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
        }
      }
    }

    // Structured rows already include the values; use scalar fallback only for older firmware.
    if (params == null) {
      Iterator<String> keys = j.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if ("type".equals(key) || "raw".equals(key) || "parameters".equals(key) ||
            "injectors".equals(key) || "spark".equals(key) || "profile".equals(key) ||
            "connected".equals(key) || "cycle".equals(key)) continue;
        Object value = j.opt(key);
        if (value == null || value instanceof JSONObject || value instanceof JSONArray) continue;
        String unit = "";
        if ("rpm".equals(key)) unit = "دور/دقیقه";
        else if ("coolant".equals(key) || "intake".equals(key)) unit = "°C";
        else if ("battery".equals(key)) unit = "V";
        else if ("map".equals(key)) unit = "bosch_zamyad_7971".equals(j.optString("profile")) ?
            "mbar (فرمول مرجع)" : "kPa (تأییدنشده)";
        upsertDynamicParameter("json_" + key, friendlyParameterName(key), String.valueOf(value), unit, "");
      }
    }
    // Avoid continuously updating ~93 extra hidden raw-byte rows while
    // the user is on the Pride dashboard. Show them only on their own page.
    JSONArray raw = (currentScreen == dynamicParametersSection) ? j.optJSONArray("raw") : null;
    if (raw != null) {
      for (int i = 0; i < raw.length(); i++) {
        int b = raw.optInt(i, -1);
        if (b < 0) continue;
        String hex = String.format(java.util.Locale.US, "%02X", b & 0xFF);
        upsertDynamicParameter("raw_" + i, String.format(java.util.Locale.US, "داده خام d[%02d]", i),
            b + " (0x" + hex + ")", "", "خام؛ فرمول شناسایی نشده");
      }
    }

    JSONArray inj = j.optJSONArray("injectors");
    if (inj != null) for (int i=0; i<inj.length(); i++)
      upsertDynamicParameter("inj_" + i, "زمان پاشش انژکتور " + (i+1), inj.optString(i), "ms", "نامزد");
    JSONArray ign = j.optJSONArray("spark");
    if (ign != null) for (int i=0; i<ign.length(); i++)
      upsertDynamicParameter("spark_" + i, "آوانس جرقه سیلندر " + (i+1), ign.optString(i), "°", "نامزد");
  }


  // Values from V47 arrive in 7 numbered, low-bandwidth packets per ECU sample.
  // Stage the values and refresh the entire on-screen table together.
  void updateCompactPride(JSONObject j) {
    int cycle = j.optInt("s", -1);
    int start = j.optInt("i", -1);
    JSONArray values = j.optJSONArray("v");
    if (cycle < 0 || start < 0 || start >= PRIDE_IDS.length ||
        values == null || start + values.length() > PRIDE_IDS.length) {
      setStatus("بسته زیمنس پراید نامعتبر یا خارج از محدوده بود");
      return;
    }
    if (pridePendingCycle != -1 && pridePendingCycle != cycle) {
      // Last cycle lacked its closing packet. Report what did arrive, not stale values.
      renderPrideCycle(false);
    }
    if (pridePendingCycle != cycle) {
      pridePendingCycle = cycle;
      java.util.Arrays.fill(pridePendingValues, null);
      java.util.Arrays.fill(pridePendingPresent, false);
    }
    for (int k = 0; k < values.length(); k++) {
      int idx = start + k;
      if (values.isNull(k)) continue;
      Object rawValue = values.opt(k);
      if (rawValue == null || rawValue == JSONObject.NULL) continue;
      String shown = String.valueOf(rawValue);
      String unit = PRIDE_UNITS[idx];
      if (!unit.isEmpty()) shown += " " + unit;
      if (suspectPrideNumber(idx, rawValue)) shown += " ⚠";
      pridePendingValues[idx] = shown;
      pridePendingPresent[idx] = true;
    }
    if (start + values.length() >= PRIDE_IDS.length) {
      renderPrideCycle(true);
      pridePendingCycle = -1;
    }
  }

  boolean suspectPrideNumber(int idx, Object data) {
    if (data instanceof String) return false;
    double x;
    try { x = Double.parseDouble(String.valueOf(data)); }
    catch (Exception ignored) { return true; }
    if (Double.isNaN(x) || Double.isInfinite(x)) return true;
    if (idx == 0) return x < 6 || x > 18;                  // battery
    if (idx == 1) return x < -45 || x > 140;               // intake
    if (idx == 2) return x < 0 || x > 5.5;                 // TPS voltage
    if (idx == 3) return x < 0 || x > 110;                 // TPS angle
    if (idx == 4) return x < 0 || x > 2200;                // MAP mbar
    if (idx == 5) return x < 0 || x > 9000;                // RPM
    if (idx == 11) return x < 0 || x > 250;                // speed
    if (idx == 12 || (idx >= 26 && idx <= 30)) return x < 0 || x > 75;
    if (idx == 17 || idx == 18) return x < 0 || x > 5.5;   // oxygen volt
    if (idx == 21) return x < -45 || x > 150;              // coolant
    if (idx >= 31 && idx <= 34) return x < -35 || x > 75; // spark
    return false;
  }


  void sendPrideDeliveryReport(final int cycle, final int count) {
    if (cycle < 0) return;
    // Acknowledgment is written to the same Wi-Fi TCP link as ECU commands,
    // but does not trigger a K-line command or delay the UI thread.
    prideAckWorker.execute(() -> {
      try {
        synchronized (this) {
          if (socket == null || socket.isClosed() || out == null) return;
          out.write(("@" + cycle + "," + count + "\n").getBytes("US-ASCII"));
          out.flush();
        }
      } catch (Exception e) {
        android.util.Log.w("YasirDiag", "Could not report Pride delivery", e);
      }
    });
  }

  void renderPrideCycle(boolean finishedWithLastPacket) {
    int received = 0;
    int warnings = 0;
    int keptOld = 0;
    long now = android.os.SystemClock.elapsedRealtime();
    for (int idx = 0; idx < PRIDE_IDS.length; idx++) {
      TextView target = prideSiemensValues.get(PRIDE_IDS[idx]);
      String shown;
      boolean stale = false;
      if (pridePendingPresent[idx]) {
        received++;
        shown = pridePendingValues[idx];
        prideCurrentValues[idx] = shown;
        prideLastSeenAt[idx] = now;
      } else if (prideCurrentValues[idx] != null &&
                 !prideCurrentValues[idx].equals("—")) {
        keptOld++;
        stale = true;
        shown = prideCurrentValues[idx] + "  • قدیمی";
      } else {
        shown = "—";
        stale = true;
      }
      if (shown.contains("⚠")) warnings++;
      if (target != null) {
        if (!shown.contentEquals(target.getText())) target.setText(shown);
        target.setTextColor(stale ? Color.rgb(156, 171, 182) :
              shown.contains("⚠") ? Color.rgb(255, 180, 69) : teal);
      }
    }
    prideInvalidValues = warnings;
    boolean complete = received == 35 && finishedWithLastPacket;
    if (complete) prideCompleteCycles++;
    else prideIncompleteCycles++;

    String rate = "";
    if (complete) {
      if (prideLastCompleteAt != 0 && now > prideLastCompleteAt) {
        float hz = 1000.0f / (now - prideLastCompleteAt);
        rate = String.format(java.util.Locale.US, " • %.2f Hz", hz);
      }
      prideLastCompleteAt = now;
    }
    if (prideSiemensUpdated != null) {
      String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date());
      prideSiemensUpdated.setText("دریافت تازه: " + received + "/۳۵ • " +
          (complete ? "کامل" : "ناقص") +
          " • نگهداری قبلی: " + keptOld +
          " • هشدار: " + warnings + rate + " • " + time);
    }

    // The STM32 V49 adjusts its cadence ONLY from confirmed receiver counts.
    // Never claim a full cycle based on the last packet alone.
    sendPrideDeliveryReport(pridePendingCycle, received);
    if (!complete) {
      setStatus("زیمنس پراید: " + received + "/۳۵ تازه؛ " +
                keptOld + " مقدار قبلی با برچسب قدیمی نگه داشته شد");
    } else {
      setStatus("زیمنس پراید: ۳۵/۳۵ کامل • دریافت پایدار");
    }
  }

  void parse(String line) {
    try {
      JSONObject j = new JSONObject(line);
      if ("p".equals(j.optString("t"))) {
        updateCompactPride(j);
        if (autoDetectionActive && autoDetectionStage == 2) {
          selectedEcu = "زیمنس پراید";
          finishAutomaticDetection("دادهٔ زنده با پروفایل زیمنس پراید سازگار است؛ شناسهٔ کارخانه‌ای جداگانه اعلام نشد.");
        }
        return;
      }
      String type = j.optString("type");
      if (type.equals("ecu_info")) {
        String maker = j.optString("manufacturer", "UNKNOWN");
        String makerFa = "UNKNOWN".equalsIgnoreCase(maker) ? "در پاسخ ECU اعلام نشده" : maker;
        StringBuilder info = new StringBuilder("ECU روی CAN پاسخ داد");
        appendEcuInfoLine(info, "سازنده", makerFa);
        appendEcuInfoLine(info, "نام ECU", j.optString("ecu_name", ""));
        appendEcuInfoLine(info, "شناسه کالیبراسیون", j.optString("calibration_id", ""));
        appendEcuInfoLine(info, "CVN", j.optString("cvn", ""));
        appendEcuInfoLine(info, "VIN", j.optString("vin", ""));
        appendEcuInfoLine(info, "شبکه", j.optString("protocol", ""));
        updateAutoSearchResult(info.toString());
        if (autoDetectionActive && autoDetectionStage == 3) {
          selectedEcu = "OBD-II CAN";
          finishAutomaticDetection(info.toString());
        }
        setStatus("اطلاعات شناسایی استاندارد ECU دریافت شد");
      } else if(type.equals("parameters")) {
        updateDynamicParameters(j);
        if (autoDetectionActive && autoDetectionStage == 1 &&
            "bosch_zamyad_7971".equals(j.optString("profile"))) {
          selectedEcu = "بوش زامیاد 7.9.7.1";
          finishAutomaticDetection("پاسخ داده با پروفایل بوش زامیاد 7.9.7.1 سازگار است؛ شناسهٔ کارخانه‌ای جداگانه اعلام نشد.");
        }

        if ("bosch_zamyad_7971".equals(j.optString("profile"))) {
          if (boschZamyadUpdated != null) boschZamyadUpdated.setText("بوش زامیاد • دریافت تازه: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
          setStatus("۱۹ پارامتر زنده بوش زامیاد دریافت شد");
          if (!autoDetectionActive && isBoschSelected()) showEcuActionsMenu();
        } else if ("sb13_can_obd".equals(j.optString("profile"))) {
          if (sb13CanUpdated != null) sb13CanUpdated.setText("آخرین دریافت پارامترهای CAN: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
          setStatus("پارامتر زندهٔ SB13 از CAN دریافت شد");
          if (!autoDetectionActive) showEcuActionsMenu();
        } else {
          prideFrames++;
          setStatus("بسته پارامترهای زیمنس پراید دریافت شد: " + prideFrames);
        }
      } else if(type.equals("telemetry")) {
        telemetryFrames++;
        if (autoDetectionActive && autoDetectionStage == 2) {
          selectedEcu = "زیمنس پراید";
          finishAutomaticDetection("دادهٔ زنده با پروفایل زیمنس پراید سازگار است؛ شناسهٔ کارخانه‌ای جداگانه اعلام نشد.");
        }
        updateDynamicParameters(j);
        rpm.setText(j.optString("rpm","—") + " دور/دقیقه");
        coolant.setText(j.optString("coolant","—") + " °C");
        intake.setText(j.optString("intake","—") + " °C");
        battery.setText(j.optString("battery","—") + " ولت");
        map.setText(j.optString("map","—") + " (تأییدنشده)");
        dashRpm.setText(j.optString("rpm","—") + " دور/دقیقه");
        dashCoolant.setText(j.optString("coolant","—") + " °C");
        dashIntake.setText(j.optString("intake","—") + " °C");
        dashBattery.setText(j.optString("battery","—") + " ولت");
        dashUpdated.setText("آخرین دریافت: " + new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date()));
        JSONArray inj = j.optJSONArray("injectors");
        JSONArray ign = j.optJSONArray("spark");
        updateTable(inj,injectorValues,"ms");
        updateTable(ign,sparkValues,"°");
        if(inj != null && inj.length()>0) {
          double sum=0; int count=0;
          for(int i=0;i<inj.length();i++) if(!inj.isNull(i)) {
            try {sum+=inj.getDouble(i);count++;}catch(Exception ignored){}
          }
          String average = count>0 ? String.format(java.util.Locale.US,"%.3f ms",sum/count) : "—";
          injectorAverage.setText(average);
          dashInjection.setText(average);
        } else { injectorAverage.setText("—"); dashInjection.setText("—"); }
        if ("bosch_zamyad_7971".equals(j.optString("profile")))
          setStatus("پارامترهای زنده بوش زامیاد دریافت می‌شود");
        else
          setStatus("داده زنده ECU دریافت می‌شود");
      } else if(type.equals("dtc")) {
        JSONArray codes = j.optJSONArray("codes");
        boolean isBoschDtc = "bosch_zamyad_7971".equals(j.optString("profile"));
        StringBuilder b = new StringBuilder("تعداد خطا: " + j.optInt("count") + "\n");
        if(codes != null)for(int i=0;i<codes.length();i++) {
          JSONObject c = codes.optJSONObject(i);
          if(c!=null) {
            String rawCode = c.optString("code");
            String code = isBoschDtc ? boschStandardCode(rawCode) : standardCode(rawCode);
            if (isBoschDtc) {
              b.append("کد عمومی متناظر: ").append(code)
               .append("  |  کد خام Bosch: ").append(rawCode)
               .append("  |  وضعیت: ").append(c.optString("status")).append("\n");
              b.append(boschDtcDescription(code)).append("\n");
              b.append("این تطبیق از دو بایت اول است و برای ECU بوش زامیاد باید با مرجع خودرو تأیید شود.\n\n");
            } else {
              b.append(code).append("  |  وضعیت: ").append(c.optString("status")).append("\n")
               .append(DtcDescriptions.describe(code)).append("\n\n");
            }
          }
        }
        dtc.setText(b.toString());
        if (boschFaultValues != null) boschFaultValues.setText(b.toString());
      } else if(type.equals("ack")) {
        setStatus("دریافت فرمان از اپلیکیشن: " + j.optString("command"));
      } else if(type.equals("status")) {
        String message = j.optString("message", "نامشخص");
        if (message.equals("DTC_READ_OK")) {
          setStatus("خواندن خطاها با موفقیت انجام شد");
        } else if (message.equals("DTC_CLEAR_OK")) {
          setStatus("فرمان پاک‌کردن خطاها به ECU ارسال و پذیرفته شد");
        } else if (message.equals("DTC_CLEAR_FAILED")) {
          setStatus("پاک‌کردن خطاها ناموفق بود؛ ECU پاسخ مثبت نداد");
        } else if (message.startsWith("OBD_CAN_CONNECTED_")) {
          autoCanConnected = true;
          String net = message.substring("OBD_CAN_CONNECTED_".length()).replace("_", " ");
          setStatus("اتصال CAN برقرار شد • " + net);
          updateAutoSearchResult("پاسخ ECU روی CAN دریافت شد • " + net +
              "\nدر حال خواندن VIN، نام ECU و شناسه‌های استاندارد...");
          if (autoDetectionActive && autoDetectionStage == 3) {
            selectedEcu = "OBD-II CAN";
            send("!7");
            ui.postDelayed(() -> { if (tcpLive) send("!6"); }, 1600);
            finishAutomaticDetection("ECU روی CAN شناسایی شد • " + net + " • پارامترهای زنده در حال دریافت‌اند");
          } else if ("SB13".equals(selectedEcu)) {
            send("!6");
            showEcuActionsMenu();
          }
        } else if (message.startsWith("CAN_PROBING_")) {
          setStatus("در حال جست‌وجوی پاسخ ECU روی CAN: " + message.replace("CAN_PROBING_", ""));
          updateAutoSearchResult("در حال جستجوی ECU پاسخ‌گو روی CAN...\n" + message.replace("CAN_PROBING_", ""));
        } else if (message.equals("OBD_CAN_NO_RESPONSE")) {
          setStatus("در این شبکه پاسخی برای OBD-II CAN دریافت نشد");
          if (autoDetectionActive && autoDetectionStage == 3)
            finishAutomaticDetection("پاسخی از بوش K-Line، زیمنس K-Line یا OBD-II CAN دریافت نشد. مدل‌های دیگر باید با پروتکل تأییدشدهٔ خود به کاتالوگ اضافه شوند.");
          else updateAutoSearchResult("پاسخی از ECU روی OBD-II CAN دریافت نشد. انتخاب دستی سیستم را باز کنید.");
        } else if (message.equals("OBD_CAN_NOT_CONNECTED")) {
          setStatus("ابتدا ECU را از راه CAN شناسایی و متصل کنید");
        } else if (message.equals("OBD_CAN_DISCONNECTED")) {
          setStatus("ارتباط CAN با ECU قطع شد");
        } else if (message.equals("LIVE_REQUESTED")) {
          setStatus("درخواست پارامترهای زنده ارسال شد؛ منتظر پاسخ ECU هستیم");
        } else if (message.equals("DTC_READ_FAILED")) {
          setStatus("خواندن خطاهای OBD-II ناموفق بود؛ پاسخ ECU دریافت نشد");
        } else if (message.equals("DTC_CLEAR_OK_NO_CODES")) {
          dtc.setText("خطایی در فهرست ثبت نشد.");
          setStatus("خواندن خطاها موفق بود؛ خطایی ثبت نشده است");
        } else if (message.equals("DTC_READ_OK_NO_CODES")) {
          dtc.setText("هیچ خطایی در ECU ثبت نشده است.");
          setStatus("خواندن خطاها موفق بود؛ خطایی ثبت نشده");
        } else if (message.startsWith("DTC_READ_FAILED")) {
          setStatus("خواندن خطا ناموفق بود: " + message);
        } else if (message.equals("DTC_CLEAR_ACCEPTED_RECHECKING")) {
          setStatus("فرمان پاک‌کردن پذیرفته شد؛ در حال بررسی مجدد خطاها...");
        } else if (message.startsWith("DTC_CLEAR_FAILED")) {
          setStatus("پاک‌کردن خطا تأیید نشد: " + message);
        } else if (message.equals("DTC_CLEARING")) {
          setStatus("در حال پاک‌کردن خطاها...");
        } else if (message.equals("ACTUATOR_REQUEST_ACCEPTED")) {
          setStatus("ECU فرمان تست را پذیرفت؛ حرکت فیزیکی عملگر باید جداگانه بررسی شود");
        } else if (message.equals("ACTUATOR_REQUEST_NOT_CONFIRMED")) {
          setStatus("تست عملگر تأیید نشد؛ پاسخ ECU را بررسی کنید");
        } else if (message.equals("ACTUATOR_BLOCKED_NOT_ARMED")) {
          setStatus("تست اجرا نشد: آماده‌سازی X انجام نشده یا منقضی شده است");
        } else if (message.equals("ACTUATOR_FAILED_NOT_CONNECTED")) {
          setStatus("تست عملگر انجام نشد: ابتدا ECU را وصل کنید");
        } else if (message.equals("ACTUATOR_ALL_OFF_ACCEPTED")) {
          setStatus("فرمان توقف همه تست‌ها توسط ECU پذیرفته شد");
        } else if (message.equals("ACTUATOR_ALL_OFF_SENT")) {
          setStatus("فرمان توقف همه تست‌ها ارسال شد؛ تأیید ECU در انتظار است");
        } else if (message.equals("BOSCH_ECU_CONNECTED")) {
          setStatus("پاسخ K-Line بوش دریافت شد؛ در حال خواندن پارامترها");
          if (autoDetectionActive && autoDetectionStage == 1) {
            selectedEcu = "بوش زامیاد 7.9.7.1";
            sendBoschKey('6');
            finishAutomaticDetection("ECU بوش روی K-Line شناسایی شد؛ پارامترهای زنده در حال دریافت‌اند");
          } else if ("بوش زامیاد 7.9.7.1".equals(selectedEcu)) {
            sendBoschKey('6');
            showEcuActionsMenu();
          }
        } else if (message.equals("BOSCH_ECU_CONNECTION_FAILED")) {
          setStatus("اتصال ECU بوش برقرار نشد؛ سوئیچ، سیم K-Line و پروفایل را بررسی کنید");
          if (autoDetectionActive && autoDetectionStage == 1) beginAutomaticDetectionStage(2);
        } else if (message.equals("ECU_CONNECTED")) {
          setStatus("اتصال K-Line زیمنس برقرار شد؛ دریافت پارامترها ادامه دارد");
          if (autoDetectionActive && autoDetectionStage == 2) {
            selectedEcu = "زیمنس پراید";
            finishAutomaticDetection("ECU زیمنس روی K-Line شناسایی شد؛ پارامترهای زنده در حال دریافت‌اند");
          } else if (isSiemensSelected()) showEcuActionsMenu();
        } else if (message.equals("ECU_CONNECTION_FAILED")) {
          setStatus("اتصال K-Line زیمنس برقرار نشد؛ سیم‌بندی و برق ECU را بررسی کنید");
          if (autoDetectionActive && autoDetectionStage == 2) beginAutomaticDetectionStage(3);
        } else if (message.equals("BOSCH_PARAMETER_READ_FAILED")) {
          setStatus("ECU بوش وصل شد، اما دریافت پارامتر ناموفق بود؛ وضعیت اتصال و پروفایل را بررسی کنید");
        } else if (message.equals("BOSCH_DTC_CLEAR_NOT_CONFIRMED")) {
          setStatus("پاک‌کردن خطاهای بوش توسط ECU تأیید نشد");
        } else if (message.equals("BOSCH_DTC_CLEAR_ACCEPTED_RECHECKING")) {
          setStatus("فرمان پاک‌کردن خطاهای بوش پذیرفته شد؛ در حال خواندن مجدد خطاها");
        } else if (message.equals("BOSCH_DTC_READ_FINISHED_RAW_CODES")) {
          setStatus("کدهای خطای خام بوش دریافت شدند؛ تطبیق نام کدها محدود است");
        } else if (message.equals("BOSCH_ACTUATOR_ON_ACCEPTED")) {
          setStatus("ECU فرمان روشن‌کردن عملگر بوش را پذیرفت؛ حرکت فیزیکی را بررسی کنید");
        } else if (message.equals("BOSCH_ACTUATOR_OFF_ACCEPTED")) {
          setStatus("ECU فرمان خاموش‌کردن عملگر بوش را پذیرفت");
        } else if (message.equals("BOSCH_ACTUATOR_EARLY_STOP_REQUESTED")) {
          setStatus("درخواست توقف زودهنگام ثبت شد؛ فرمان خاموشی ارسال می‌شود");
        } else if (message.equals("BOSCH_ACTUATOR_ON_NOT_CONFIRMED")) {
          setStatus("فرمان روشن‌کردن عملگر بوش تأیید نشد");
        } else if (message.equals("BOSCH_ACTUATOR_OFF_NOT_CONFIRMED") || message.equals("BOSCH_ACTUATOR_OFF_NOT_CONFIRMED_SWITCH_IGNITION_OFF")) {
          setStatus("هشدار: خاموشی عملگر بوش تأیید نشد؛ سوئیچ را خاموش و بررسی کنید");
        } else if (message.equals("BOSCH_ACTUATOR_TEST_FINISHED_OFF_CONFIRMED")) {
          setStatus("تست عملگر بوش تمام شد و فرمان خاموشی تأیید شد");
        } else if (message.equals("BOSCH_ACTUATOR_ON_NOT_CONFIRMED_OFF_CONFIRMED")) {
          setStatus("فرمان روشن‌شدن عملگر تأیید نشد؛ فرمان خاموشی تأیید شد");
        } else if (message.equals("BOSCH_MIL_ON_NOT_CONFIRMED_OFF_CONFIRMED")) {
          setStatus("روشن‌شدن چراغ چک تأیید نشد؛ فرمان خاموشی تأیید شد");
        } else if (message.equals("BOSCH_MIL_TEST_FINISHED_OFF_CONFIRMED")) {
          setStatus("تست چراغ چک بوش پایان یافت؛ خاموشی تأیید شد");
        } else if (message.equals("PROFILE_BOSCH_SELECTED")) {
          setStatus("پروفایل Bosch Zamyad 7.9.7.1 فعال شد");
        } else if (message.equals("PROFILE_SIEMENS_SELECTED")) {
          setStatus("پروفایل زیمنس فعال شد");
        } else if (message.equals("ECU_CONNECTED")) {
          setStatus("پاسخ K-Line دریافت شد؛ منتظر دادهٔ زندهٔ زیمنس هستیم");
        } else if (message.equals("ECU_CONNECTION_FAILED")) {
          setStatus("پروفایل زیمنس پاسخ نداد؛ بررسی OBD-II روی CAN شروع می‌شود");
          if (autoDetectionActive && autoDetectionStage == 2) beginAutomaticDetectionStage(3);
        } else if (message.equals("CAN_ID_INFO_COMPLETE")) {
          setStatus("شناسه‌های استاندارد ECU دریافت شد");
        } else if (message.equals("CAN_ID_INFO_UNAVAILABLE")) {
          if (autoDetectionActive && autoDetectionStage == 3)
            finishAutomaticDetection("ECU روی CAN پاسخ می‌دهد، اما نام ECU و شناسهٔ Calibration را در Mode 09 ارائه نکرد.");
        } else if (message.equals("ACTUATOR_COMMAND_SENT_WAITING_ECU")) {
          setStatus("فرمان عملگر ارسال شد؛ منتظر پاسخ ECU...");
        } else {
          setStatus("وضعیت: " + message);
        }
      }
    } catch (JSONException ex) {
      malformedFrames++;
      setStatus("JSON ناقص یا خراب: " + malformedFrames + "، طول پیام " + line.length());
      android.util.Log.w("YasirDiag", "Malformed JSON, length=" + line.length(), ex);
    }
  }

  String array(JSONArray a) {
    if(a==null)return "—";
    StringBuilder s = new StringBuilder();
    for(int i=0;i<a.length();i++) {
      if(i>0)s.append(" | ");
      s.append(i+1).append(": ").append(a.optString(i));
    }
    return s.toString();
  }

  synchronized void closeSocket() {
    try {if(socket!=null)socket.close();}catch(Exception ignored){}
    socket = null;
    out = null;
  }

  void disconnect() {
    updateTcpBadge(false, false);
    tcpLive = false;
    connectionEpoch++;
    running = false;
    closeSocket();
    setStatus("اتصال قطع شد");
  }

  @Override protected void onDestroy() {
    ui.removeCallbacks(showRealTcpLoss);
    exiting = true;
    prideAckWorker.shutdownNow();
    disconnect();
    super.onDestroy();
  }
}
