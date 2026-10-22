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
    button("پارامترها", () -> show(parametersMenu));
    button("عملگرها", () -> show(actuatorSection));
    button("ریست کردن مقادیر سازگار یافته", () -> show(resetSection));
    button("نمودار", () -> show(chartSection));
    button("قطع اتصال", () -> disconnect());

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
    spark = field("اصلاح جرقه سیلندرها");
    back(parametersMenu);

    content = airSection;
    intake = field("دمای هوای ورودی");
    map = field("فشار منیفولد (آزمایشی / تأییدنشده)");
    back(parametersMenu);

    content = fuelSection;
    injectors = field("زمان پاشش انژکتورها");
    back(parametersMenu);

    content = dtcSection;
    button("خواندن خطاها", () -> send("!2"));
    button("پاک کردن خطاها", () -> confirmClear());
    dtc = field("خطاهای ECU");
    back(home);

    content = ecuSection;
    content.addView(text("ECU: Siemens SIM2K-34VR (پروفایل انتخاب‌شده)؛ شناسایی خودکار هنوز پیاده‌سازی نشده است.",16,false));
    back(home);
    content = actuatorSection;
    content.addView(text("تست عملگرها تا تأیید فرمان‌های اختصاصی ECU غیرفعال است.",16,false));
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
        injectors.setText(array(j.optJSONArray("injectors")));
        spark.setText(array(j.optJSONArray("spark")));
        setStatus("داده زنده ECU دریافت می‌شود");
      } else if(type.equals("dtc")) {
        JSONArray codes = j.optJSONArray("codes");
        StringBuilder b = new StringBuilder("تعداد خطا: " + j.optInt("count") + "\n");
        if(codes != null)for(int i=0;i<codes.length();i++) {
          JSONObject c = codes.optJSONObject(i);
          if(c!=null)b.append(c.optString("code")).append("   وضعیت: ").append(c.optString("status")).append("\n");
        }
        dtc.setText(b.toString());
      } else if(type.equals("ack")) {
        setStatus("تأیید دریافت فرمان: " + j.optString("command"));
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
