package ir.yasir.diag;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;

public class MainActivity extends Activity {
  final Handler ui = new Handler(Looper.getMainLooper());
  volatile boolean running = false;
  Socket socket;
  OutputStream out;
  TextView status, rpm, coolant, intake, battery, map, injectors, spark, dtc;
  LinearLayout content, root, home, parametersMenu, speedSection, emissionsSection, ignitionSection, airSection, fuelSection, dtcSection, ecuSection, actuatorSection, resetSection, chartSection;
  LinearLayout currentScreen;
  TextView[] injectorValues = new TextView[5];
  TextView[] sparkValues = new TextView[4];
  TextView injectorAverage, dashRpm, dashCoolant, dashBattery, dashIntake, dashInjection, dashUpdated;
  LinearLayout dashboardSection;
  final String host = "192.168.4.1";
  final int port = 3333;
  final int navy = Color.rgb(13,26,44), teal = Color.rgb(0,135,135);

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
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
    root.addView(text("یاسر دیاگ",29,true));
    root.addView(text("عیب‌یاب فارسی خودرو • زیمنس SIM2K-34VR",15,false));
    status = text("ابتدا به وای‌فای E-MAX وصل شوید",15,true);
    status.setTextColor(teal);
    root.addView(status);

    home = section("منوی اصلی");
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

    content = home;
    button("اتصال به دستگاه (Wi-Fi)", () -> connect());
    button("اتصال به ECU", () -> send("!1"));
    button("شناسایی", () -> show(ecuSection));
    button("خواندن عیب", () -> { show(dtcSection); send("!2"); });
    button("پاک کردن عیب", () -> { show(dtcSection); confirmClear(); });
    button("داشبورد زنده موتور", () -> show(dashboardSection));
    button("پارامترها", () -> show(parametersMenu));
    button("عملگرها", () -> show(actuatorSection));
    button("ریست کردن مقادیر سازگار یافته", () -> show(resetSection));
    button("نمودار", () -> show(chartSection));
    button("قطع اتصال", () -> disconnect());

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
    content = root;
    show(home);
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
          out.write("!X\\n".getBytes("US-ASCII"));
          out.flush();
          Thread.sleep(250);
          out.write(("!" + Character.toUpperCase(command) + "\\n").getBytes("US-ASCII"));
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

  void parse(String line) {
    try {
      JSONObject j = new JSONObject(line);
      String type = j.optString("type");
      if(type.equals("telemetry")) {
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
