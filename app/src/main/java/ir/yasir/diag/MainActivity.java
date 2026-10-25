package ir.yasir.diag;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
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
  Socket socket;
  OutputStream out;
  TextView status, rpm, coolant, intake, battery, map, injectors, spark, dtc;
  LinearLayout content, root, home, parametersMenu, speedSection, emissionsSection, ignitionSection, airSection, fuelSection, dtcSection, ecuSection, actuatorSection, resetSection, chartSection;
  LinearLayout currentScreen, dynamicParametersSection, dynamicParameterList, ecuSelectionSection, diagnosticMenu, settingsSection, updateSection, helpSection;
  TextView selectedBrandLabel, diagnosticSelectionLabel;
  String selectedBrand = "", selectedEcu = "";
  TextView updateInfo;
  SharedPreferences preferences;
  float uiScale = 1.0f;
  int themeMode = 0;
  final HashMap<String, TextView> dynamicParameterValues = new HashMap<>();
  final HashMap<String, TextView> dynamicParameterNames = new HashMap<>();
  TextView[] injectorValues = new TextView[5];
  TextView[] sparkValues = new TextView[4];
  TextView injectorAverage, dashRpm, dashCoolant, dashBattery, dashIntake, dashInjection, dashUpdated;
  LinearLayout dashboardSection;
  final String host = "192.168.4.1";
  final int port = 3333;
  int navy = Color.rgb(13,26,44), teal = Color.rgb(0,135,135);

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    preferences = getSharedPreferences("yasir_diag_settings", MODE_PRIVATE);
    uiScale = preferences.getFloat("ui_scale", 1.0f);
    themeMode = preferences.getInt("theme_mode", 0);
    configurePalette();
    getWindow().setStatusBarColor(navy);
    ScrollView scroll = new ScrollView(this);
    root = new LinearLayout(this);
    root.setPadding(22,20,22,26);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(241,246,250));
    root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    scroll.addView(root);
    setContentView(scroll);
    content = root;
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(12, 10, 12, 10);
    header.setTag("hero-header");
    header.setBackground(panelDrawable(Color.rgb(7, 20, 19), Color.rgb(45, 126, 74), 18));
    LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
    headerParams.setMargins(0, 0, 0, 10);
    ImageView brandLogo = new ImageView(this);
    brandLogo.setImageResource(R.drawable.yasir_diag_logo);
    brandLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
    brandLogo.setBackground(panelDrawable(Color.rgb(0, 0, 0), Color.rgb(42, 112, 65), 14));
    brandLogo.setClipToOutline(true);
    header.addView(brandLogo, new LinearLayout.LayoutParams(88, 88));
    LinearLayout brandText = new LinearLayout(this);
    brandText.setOrientation(LinearLayout.VERTICAL);
    brandText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
    brandText.setPadding(12, 0, 8, 0);
    TextView appTitle = text("یاسر دیاگ", 28, true);
    appTitle.setTextColor(Color.rgb(235, 255, 239));
    brandText.addView(appTitle);
    TextView appTagline = text("تخصص ما، آرامش خودروی شما", 13, false);
    appTagline.setTextColor(Color.rgb(135, 235, 166));
    brandText.addView(appTagline);
    header.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(header, headerParams);

    LinearLayout connectionPanel = new LinearLayout(this);
    connectionPanel.setOrientation(LinearLayout.VERTICAL);
    connectionPanel.setPadding(10, 7, 10, 7);
    connectionPanel.setTag("connection-panel");
    connectionPanel.setBackground(panelDrawable(Color.rgb(11, 30, 29), Color.rgb(28, 75, 65), 14));
    status = text("● TCP: متصل نیست؛ ابتدا به دستگاه وصل شوید", 14, true);
    status.setTextColor(Color.rgb(83, 224, 183));
    connectionPanel.addView(status);
    TextView endpoint = text("دستگاه E-MAX  •  192.168.4.1:3333", 12, false);
    endpoint.setTextColor(Color.rgb(174, 197, 189));
    connectionPanel.addView(endpoint);
    root.addView(connectionPanel);

    home = section("انتخاب سازنده خودرو");
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
    dynamicParametersSection = section("پارامترهای زنده و قابل توسعه");
    ecuSelectionSection = section("انتخاب ECU");
    diagnosticMenu = section("منوی عیب‌یابی");
    settingsSection = section("تنظیمات ظاهری و برنامه");
    updateSection = section("به‌روزرسانی برنامه");
    helpSection = section("راهنما و اطلاعات برنامه");

    content = home;
    content.addView(sectionTitle("انتخاب سازنده خودرو", "۸ گروه خودرو", "🚘"));
    android.widget.GridLayout companyGrid = new android.widget.GridLayout(this);
    companyGrid.setColumnCount(2);
    companyGrid.setUseDefaultMargins(false);
    companyGrid.setAlignmentMode(android.widget.GridLayout.ALIGN_BOUNDS);
    companyGrid.setTag("company-grid");
    home.addView(companyGrid, new LinearLayout.LayoutParams(-1, -2));
    addIndustrialCompanyCard(companyGrid, "ایران خودرو", 0, "IR");
    addIndustrialCompanyCard(companyGrid, "سایپا", 1, "SA");
    addIndustrialCompanyCard(companyGrid, "پارس خودرو", 2, "PA");
    addIndustrialCompanyCard(companyGrid, "بهمن و مزدا", 3, "BM");
    addIndustrialCompanyCard(companyGrid, "دیار خودرو", 4, "DY");
    addIndustrialCompanyCard(companyGrid, "کرمان موتور", 5, "KM");
    addIndustrialCompanyCard(companyGrid, "رنو", 6, "RN");
    addIndustrialCompanyCard(companyGrid, "سایر خودروها", 7, "•••");

    home.addView(sectionTitle("دسترسی سریع", "ابزارهای پرکاربرد", "⚡"));
    android.widget.GridLayout quickGrid = new android.widget.GridLayout(this);
    quickGrid.setColumnCount(2);
    quickGrid.setUseDefaultMargins(false);
    home.addView(quickGrid, new LinearLayout.LayoutParams(-1, -2));
    addQuickCard(quickGrid, "عیب‌یابی سریع", "خواندن و پاک‌کردن خطاها", "⌕", Color.rgb(0, 174, 235), () -> { selectedBrand = selectedBrand.isEmpty() ? "خودرو انتخاب‌نشده" : selectedBrand; selectedEcu = "زیمنس SIM2K-34VR"; diagnosticSelectionLabel.setText("خودرو: " + selectedBrand + "   |   ECU: " + selectedEcu); show(diagnosticMenu); });
    addQuickCard(quickGrid, "داده‌های زنده", "مشاهده پارامترهای لحظه‌ای", "▥", Color.rgb(0, 214, 133), () -> { if (!"زیمنس SIM2K-34VR".equals(selectedEcu)) selectedEcu = "زیمنس SIM2K-34VR"; show(dashboardSection); });
    addQuickCard(quickGrid, "تست عملگرها", "تست‌های تعمیرگاهی", "⚙", Color.rgb(255, 176, 0), () -> show(actuatorSection));
    addQuickCard(quickGrid, "اطلاعات ECU", "شناسه و پروفایل انتخابی", "▤", Color.rgb(181, 93, 255), () -> show(ecuSection));

    home.addView(sectionTitle("تنظیمات و ابزارها", "شخصی‌سازی و نگهداری", "⚙"));
    android.widget.GridLayout toolsGrid = new android.widget.GridLayout(this);
    toolsGrid.setColumnCount(3);
    toolsGrid.setUseDefaultMargins(false);
    home.addView(toolsGrid, new LinearLayout.LayoutParams(-1, -2));
    addUtilityCard(toolsGrid, "تنظیمات", "برنامه", "⚙", () -> show(settingsSection));
    addUtilityCard(toolsGrid, "ظاهر", "رنگ و اندازه", "▣", () -> show(settingsSection));
    addUtilityCard(toolsGrid, "به‌روزرسانی", "نسخه جدید", "⟳", () -> { refreshUpdateInfo(); show(updateSection); });
    addUtilityCard(toolsGrid, "گزارش‌ها", "خطا و داده", "▤", () -> show(dtcSection));
    addUtilityCard(toolsGrid, "راهنما", "اطلاعات نسخه", "ⓘ", () -> show(helpSection));
    addUtilityCard(toolsGrid, "اتصال", "E-MAX", "⌁", () -> connect());
    button("ورود به عیب‌یابی زیمنس SIM2K-34VR", () -> { selectedBrand = selectedBrand.isEmpty() ? "خودرو انتخاب‌نشده" : selectedBrand; selectedEcu = "زیمنس SIM2K-34VR"; diagnosticSelectionLabel.setText("خودرو: " + selectedBrand + "   |   ECU: " + selectedEcu); show(diagnosticMenu); });

    content = ecuSelectionSection;
    selectedBrandLabel = text("شرکت انتخابی: —",18,true);
    content.addView(selectedBrandLabel);
    content.addView(text("نوع ECU را انتخاب کنید. فقط پروفایل زیمنس SIM2K-34VR در حال حاضر به فریم‌ور متصل است؛ سایر موارد فعلاً در فهرست انتخاب هستند و تا آماده‌شدن پروفایل اختصاصی فرمان عیب‌یابی نمی‌فرستند.",14,false));
    button("زیمنس SIM2K-34VR — پروفایل فعال فعلی", () -> chooseEcu("زیمنس SIM2K-34VR"));
    button("زیمنس", () -> chooseEcu("زیمنس"));
    button("زیمنس دوگانه‌سوز", () -> chooseEcu("زیمنس دوگانه‌سوز"));
    button("ساژم (SAGEM)", () -> chooseEcu("ساژم (SAGEM)"));
    button("بوش (BOSCH)", () -> chooseEcu("بوش (BOSCH)"));
    button("والئو (VALEO)", () -> chooseEcu("والئو (VALEO)"));
    button("عظام", () -> chooseEcu("عظام"));
    button("SB13", () -> chooseEcu("SB13"));
    back(home);

    content = diagnosticMenu;
    diagnosticSelectionLabel = text("خودرو: —   |   ECU: —",15,true);
    content.addView(diagnosticSelectionLabel);
    button("اتصال به دستگاه (Wi-Fi)", () -> connect());
    button("اتصال به ECU", () -> send("!1"));
    button("شناسایی", () -> show(ecuSection));
    button("خواندن عیب", () -> { show(dtcSection); send("!2"); });
    button("پاک کردن عیب", () -> { show(dtcSection); confirmClear(); });
    button("داشبورد زنده موتور", () -> show(dashboardSection));
    button("پارامترها", () -> show(parametersMenu));
    button("همه پارامترهای زنده (پویا)", () -> show(dynamicParametersSection));
    button("عملگرها", () -> show(actuatorSection));
    button("ریست کردن مقادیر سازگار یافته", () -> show(resetSection));
    button("نمودار", () -> show(chartSection));
    button("قطع اتصال", () -> disconnect());
    button("تغییر شرکت / ECU", () -> show(home));
    button("⚙ تنظیمات برنامه", () -> show(settingsSection));
    button("به‌روزرسانی برنامه", () -> { refreshUpdateInfo(); show(updateSection); });
    button("راهنما و اطلاعات نسخه", () -> show(helpSection));
    back(home);

    content = settingsSection;
    content.addView(text("ظاهر صنعتی برنامه",18,true));
    content.addView(text("تغییرات در همین گوشی ذخیره می‌شود و پس از بستن و بازکردن برنامه باقی می‌ماند.",14,false));
    button("رنگ‌بندی: روشن صنعتی", () -> setThemeMode(0));
    button("رنگ‌بندی: آبی نفتی تیره", () -> setThemeMode(1));
    button("رنگ‌بندی: سبز صنعتی", () -> setThemeMode(2));
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
    content.addView(text("نسخه برنامه: 1.1",16,true));
    content.addView(text("ارتباط فعلی: Wi‑Fi به ESP‑01، TCP پورت 3333؛ پروفایل عملیاتی فعلی: زیمنس SIM2K‑34VR.",14,false));
    content.addView(text("سایر ECUها در فهرست انتخاب نمایش داده می‌شوند، اما تا تأیید پروتکل و آزمون واقعی، فرمان عیب‌یابی برای آن‌ها فعال نیست.",14,false));
    content.addView(text("نکته ایمنی: پاک‌کردن خطا و تست عملگر را فقط در شرایط تعمیرگاهی مناسب انجام دهید. نمایش یک فرمان به معنی تأیید عملکرد فیزیکی عملگر نیست.",14,false));
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
    content.addView(text("این فهرست از پیام‌های JSON دستگاه ساخته می‌شود. با افزودن پارامتر جدید به فریم‌ور و ارسال آن در parameters یا telemetry، نیازی به تغییر برنامه نیست.",14,false));
    dynamicParameterList = new LinearLayout(this);
    dynamicParameterList.setOrientation(LinearLayout.VERTICAL);
    dynamicParametersSection.addView(dynamicParameterList);
    back(home);

    content = root;
    applyThemeToViews(root);
    applyFontScale(root);
    show(home);
  }

  GradientDrawable panelDrawable(int fill, int stroke, int radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
    d.setStroke(Math.max(1, (int)(getResources().getDisplayMetrics().density)), stroke);
    return d;
  }

  TextView sectionTitle(String title, String subtitle, String icon) {
    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.VERTICAL);
    wrap.setPadding(2, 14, 2, 7);
    TextView heading = text(icon + "  " + title, 19, true);
    heading.setTextColor(Color.rgb(230, 255, 237));
    wrap.addView(heading);
    TextView sub = text(subtitle, 11, false);
    sub.setTextColor(Color.rgb(127, 180, 153));
    wrap.addView(sub);
    return heading;
  }

  void addIndustrialCompanyCard(android.widget.GridLayout grid, String name, int imageIndex, String code) {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER);
    card.setPadding(7, 9, 7, 8);
    card.setTag("industrial-company");
    int[] accents = new int[]{Color.rgb(0, 167, 235), Color.rgb(255, 125, 25), Color.rgb(235, 39, 55), Color.rgb(0, 194, 210), Color.rgb(170, 90, 240), Color.rgb(0, 188, 140), Color.rgb(255, 183, 0), Color.rgb(100, 130, 145)};
    int accent = accents[imageIndex % accents.length];
    card.setBackground(panelDrawable(Color.rgb(8, 22, 25), accent, 16));
    TextView badge = text(code, 13, true);
    badge.setGravity(Gravity.CENTER);
    badge.setTextColor(accent);
    badge.setPadding(4, 0, 4, 3);
    card.addView(badge);
    TextView label = text(name, 15, true);
    label.setGravity(Gravity.CENTER);
    label.setTextColor(Color.rgb(239, 248, 243));
    label.setMinHeight(36);
    card.addView(label, new LinearLayout.LayoutParams(-1, -2));
    ImageView car = new ImageView(this);
    Bitmap bitmap = vehicleBitmap(imageIndex);
    if (bitmap != null) car.setImageBitmap(bitmap);
    car.setScaleType(ImageView.ScaleType.CENTER_CROP);
    car.setBackground(panelDrawable(Color.rgb(4, 12, 15), accent, 10));
    LinearLayout.LayoutParams carParams = new LinearLayout.LayoutParams(-1, 90);
    carParams.setMargins(0, 5, 0, 5);
    card.addView(car, carParams);
    TextView enter = text("انتخاب  ›", 12, true);
    enter.setTextColor(accent);
    enter.setGravity(Gravity.CENTER);
    card.addView(enter);
    card.setOnClickListener(v -> openEcuMenu(name));
    android.widget.GridLayout.LayoutParams p = new android.widget.GridLayout.LayoutParams();
    p.width = 0;
    p.height = -2;
    p.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
    p.setMargins(4, 4, 4, 4);
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

  void openEcuMenu(String brand) {
    selectedBrand = brand;
    selectedBrandLabel.setText("شرکت انتخابی: " + brand);
    show(ecuSelectionSection);
  }

  void chooseEcu(String ecu) {
    selectedEcu = ecu;
    if ("زیمنس SIM2K-34VR".equals(ecu)) {
      diagnosticSelectionLabel.setText("خودرو: " + selectedBrand + "   |   ECU: " + selectedEcu);
      show(diagnosticMenu);
      setStatus("پروفایل فعال انتخاب شد: " + selectedBrand + " / " + selectedEcu);
      return;
    }
    new AlertDialog.Builder(this)
      .setTitle(ecu)
      .setMessage("این ECU در فهرست انتخاب ثبت شد، اما پروفایل ارتباطی و فرمول‌های اختصاصی آن هنوز به‌صورت عملیاتی تأیید نشده‌اند. برای جلوگیری از ارسال فرمان اشتباه، فعلاً تست، پاک‌کردن خطا و فرمان‌های ECU برای این انتخاب فعال نمی‌شوند.")
      .setPositiveButton("متوجه شدم", (d,w) -> show(ecuSelectionSection))
      .show();
  }

  LinearLayout section(String title) {
    LinearLayout section = new LinearLayout(this);
    section.setOrientation(LinearLayout.VERTICAL);
    section.setVisibility(View.GONE);
    root.addView(section);
    section.addView(text(title,22,true));
    return section;
  }

  void show(LinearLayout screen) {
    if(currentScreen != null) currentScreen.setVisibility(View.GONE);
    currentScreen = screen;
    screen.setVisibility(View.VISIBLE);
  }

  void back(LinearLayout target) {
    button("بازگشت", () -> show(target));
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
    if(currentScreen == home) super.onBackPressed();
    else if(currentScreen == ecuSelectionSection) show(home);
    else if(currentScreen == diagnosticMenu) show(home);
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

  void button(String title,Runnable r) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    content.addView(b);
    b.setOnClickListener(v -> r.run());
  }

  void configurePalette() {
    if (themeMode == 1) {
      navy = Color.rgb(8, 20, 35);
      teal = Color.rgb(65, 211, 196);
    } else if (themeMode == 2) {
      navy = Color.rgb(21, 69, 58);
      teal = Color.rgb(0, 126, 96);
    } else {
      navy = Color.rgb(13, 26, 44);
      teal = Color.rgb(0, 135, 135);
    }
  }

  int pageBackground() {
    if (themeMode == 1) return Color.rgb(14, 29, 47);
    if (themeMode == 2) return Color.rgb(235, 246, 240);
    return Color.rgb(241, 246, 250);
  }

  int cardBackground() {
    if (themeMode == 1) return Color.rgb(31, 49, 69);
    if (themeMode == 2) return Color.rgb(250, 255, 251);
    return Color.WHITE;
  }

  int rowBackground(boolean alternate) {
    if (themeMode == 1) return alternate ? Color.rgb(37, 57, 77) : Color.rgb(27, 44, 63);
    if (themeMode == 2) return alternate ? Color.rgb(224, 240, 231) : Color.WHITE;
    return alternate ? Color.rgb(234, 236, 240) : Color.WHITE;
  }

  int foregroundColor() {
    return themeMode == 1 ? Color.rgb(239, 247, 252) : Color.rgb(25, 34, 46);
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
      } else if ("table-row".equals(tag) || "dynamic-row".equals(tag)) {
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
    updateInfo.setText("نسخه نصب‌شده: 1.1\nبرای بررسی نسخه رسمی، دکمه «بررسی نسخه منتشرشده» را بزنید.");
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

  void setStatus(String s) { ui.post(() -> status.setText(s)); }

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

  void connect() {
    disconnect();
    running = true;
    setStatus("در حال اتصال به E-MAX ...");
    new Thread(() -> {
      while (running) {
        Socket s = null;
        try {
          s = new Socket();
          s.setKeepAlive(true);
          s.setTcpNoDelay(true);
          s.connect(new InetSocketAddress(host, port), 4500);
          if (!running) { try { s.close(); } catch(Exception ignored) {} break; }

          socket = s;
          synchronized (this) {
            out = s.getOutputStream();
          }
          setStatus("به ESP-01 متصل شد — اتصال به ECU را بزنید");

          BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
          String line;
          while (running && (line = br.readLine()) != null) {
            final String received = line;
            ui.post(() -> parse(received));
          }
          if (running) setStatus("ارتباط TCP قطع شد؛ تلاش برای اتصال مجدد...");
        } catch (Exception e) {
          if (running) {
            String detail = e.getMessage();
            if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
            setStatus("ارتباط قطع شد؛ اتصال مجدد... (" + detail + ")");
          }
        } finally {
          synchronized (this) {
            if (socket == s) {
              try { if (socket != null) socket.close(); } catch(Exception ignored) {}
              socket = null;
              out = null;
            } else {
              try { if (s != null) s.close(); } catch(Exception ignored) {}
            }
          }
        }

        if (running) {
          try { Thread.sleep(1200); }
          catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
      }
    }).start();
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

  void upsertDynamicParameter(String id, String name, String value, String unit, String statusText) {
    if (id == null || id.trim().isEmpty()) id = name;
    if (id == null || id.trim().isEmpty()) return;
    TextView val = dynamicParameterValues.get(id);
    if (val == null) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(12, 10, 12, 10);
      row.setTag("dynamic-row");
      row.setBackgroundColor((dynamicParameterList.getChildCount() % 2 == 0) ? Color.WHITE : Color.rgb(224,233,244));
      TextView label = text(name == null ? id : name, 14, false);
      label.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
      row.addView(label, new LinearLayout.LayoutParams(0, -2, 2));
      val = text("—", 16, true);
      val.setTextColor(teal);
      val.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
      row.addView(val, new LinearLayout.LayoutParams(0, -2, 3));
      dynamicParameterList.addView(row, new LinearLayout.LayoutParams(-1, -2));
      dynamicParameterValues.put(id, val);
      dynamicParameterNames.put(id, label);
    } else {
      TextView label = dynamicParameterNames.get(id);
      if (label != null && name != null && !name.trim().isEmpty()) label.setText(name);
    }
    String shown = value == null || value.equals("null") ? "—" : value;
    if (unit != null && !unit.trim().isEmpty()) shown += " " + unit;
    if (statusText != null && !statusText.trim().isEmpty()) shown += "  •  " + statusText;
    val.setText(shown);
  }

  void updateDynamicParameters(JSONObject j) {
    JSONArray params = j.optJSONArray("parameters");
    if (params != null) {
      for (int i = 0; i < params.length(); i++) {
        JSONObject p = params.optJSONObject(i);
        if (p == null) continue;
        String id = p.optString("id", p.optString("key", "param_" + i));
        String name = p.optString("name", id);
        Object rawValue = p.opt("value");
        String value = rawValue == null || rawValue == JSONObject.NULL ? "—" : String.valueOf(rawValue);
        upsertDynamicParameter(id, name, value, p.optString("unit", ""), p.optString("status", ""));
      }
    }

    // Backwards-compatible generic renderer: any future scalar JSON field is displayed automatically.
    Iterator<String> keys = j.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if ("type".equals(key) || "raw".equals(key) || "parameters".equals(key) ||
          "injectors".equals(key) || "spark".equals(key)) continue;
      Object value = j.opt(key);
      if (value == null || value instanceof JSONObject || value instanceof JSONArray) continue;
      String unit = "";
      if ("rpm".equals(key)) unit = "دور/دقیقه";
      else if ("coolant".equals(key) || "intake".equals(key)) unit = "°C";
      else if ("battery".equals(key)) unit = "V";
      else if ("map".equals(key)) unit = "kPa (تأییدنشده)";
      upsertDynamicParameter("json_" + key, friendlyParameterName(key), String.valueOf(value), unit, "");
    }

    JSONArray raw = j.optJSONArray("raw");
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

  void parse(String line) {
    try {
      JSONObject j = new JSONObject(line);
      String type = j.optString("type");
      if(type.equals("telemetry")) {
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
        setStatus("داده زنده ECU دریافت می‌شود");
      } else if(type.equals("dtc")) {
        JSONArray codes = j.optJSONArray("codes");
        StringBuilder b = new StringBuilder("تعداد خطا: " + j.optInt("count") + "\n");
        if(codes != null)for(int i=0;i<codes.length();i++) {
          JSONObject c = codes.optJSONObject(i);
          if(c!=null) {
            String code = standardCode(c.optString("code"));
            b.append(code).append("  |  وضعیت خام: ").append(c.optString("status")).append("\n");
            b.append(DtcDescriptions.describe(code)).append("\n\n");
          }
        }
        dtc.setText(b.toString());
      } else if(type.equals("ack")) {
        setStatus("دریافت فرمان از اپلیکیشن: " + j.optString("command"));
      } else if(type.equals("status")) {
        String message = j.optString("message", "نامشخص");
        if (message.equals("DTC_READ_OK")) {
          setStatus("خواندن خطاها با موفقیت انجام شد");
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
        } else if (message.equals("ACTUATOR_COMMAND_SENT_WAITING_ECU")) {
          setStatus("فرمان عملگر ارسال شد؛ منتظر پاسخ ECU...");
        } else {
          setStatus("وضعیت: " + message);
        }
      }
    } catch(JSONException ignored) {}
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
    running = false;
    closeSocket();
    setStatus("اتصال قطع شد");
  }

  @Override protected void onDestroy() {
    disconnect();
    super.onDestroy();
  }
}
