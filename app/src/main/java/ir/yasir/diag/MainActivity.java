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
  LinearLayout content;
  final String host = "192.168.4.1";
  final int port = 3333;
  final int navy = Color.rgb(13,26,44), teal = Color.rgb(0,135,135);

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(navy);
    ScrollView scroll = new ScrollView(this);
    content = new LinearLayout(this);
    content.setPadding(22,20,22,26);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setBackgroundColor(Color.rgb(241,246,250));
    content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    scroll.addView(content);
    setContentView(scroll);
    TextView heading = text("یاسر دیاگ",29,true);
    content.addView(heading);
    content.addView(text("عیب‌یاب فارسی خودرو • زیمنس SIM2K-34VR",15,false));
    status = text("ابتدا به وای‌فای E-MAX وصل شوید",15,true);
    status.setTextColor(teal);
    content.addView(status);
    button("اتصال به دستگاه", () -> connect());
    button("اتصال به ECU", () -> send("!1"));
    rpm = field("دور موتور");
    coolant = field("دمای آب");
    intake = field("دمای هوای ورودی");
    battery = field("ولتاژ باتری");
    map = field("فشار منیفولد (آزمایشی)");
    injectors = field("زمان پاشش انژکتورها");
    spark = field("اصلاح جرقه سیلندرها");
    button("خواندن خطاها", () -> send("!2"));
    button("پاک کردن خطاها", () -> new AlertDialog.Builder(this)
      .setTitle("تأیید پاک کردن خطا")
      .setMessage("آیا از پاک کردن خطاهای ECU مطمئن هستید؟")
      .setNegativeButton("انصراف",null)
      .setPositiveButton("پاک کن",(d,w) -> send("!3")).show());
    dtc = field("خطاهای ECU");
    content.addView(text("تست عملگرها و TPS تا تأیید فرمان ECU غیرفعال هستند.",14,false));
    button("قطع اتصال", () -> disconnect());
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
          byte[] bytes = (command + "\\n").getBytes("US-ASCII");
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
