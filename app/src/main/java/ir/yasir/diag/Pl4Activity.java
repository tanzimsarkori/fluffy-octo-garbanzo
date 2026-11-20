package ir.yasir.diag;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VALEO PL4_S1C front-end for the user-tested STM32 V19.5 K-Line engine.
 * Wi-Fi ESP-01 is a transparent TCP-to-USART2 bridge at 192.168.4.1:3333.
 * Requires the companion firmware in firmware/pl4/. Never send raw KWP
 * memory commands from Android: the STM32 owns all ECU safety checks.
 */
public final class Pl4Activity extends Activity {
  private static final String HOST = "192.168.4.1";
  private static final int PORT = 3333;
  private static final int BG = Color.rgb(8,20,35);
  private static final int CARD = Color.rgb(15,40,62);
  private static final int TEAL = Color.rgb(27,184,170);
  private static final int WHITE = Color.rgb(230,245,251);
  private final Object writeLock = new Object();
  private volatile Socket socket;
  private volatile OutputStream output;
  private volatile boolean connectedToEcu = false;
  private volatile boolean liveRequested = false;
  private volatile int socketEpoch = 0;

  private TextView statusText;
  private TextView dtcText;
  private LinearLayout liveRows;
  private LinearLayout actuatorRows;
  private final Map<String,TextView> liveValues = new LinkedHashMap<>();
  private final StringBuilder faultReport = new StringBuilder();

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(BG);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);
    LinearLayout root = column();
    root.setPadding(dp(14),dp(18),dp(14),dp(30));
    root.setBackgroundColor(BG);
    scroll.addView(root);
    setContentView(scroll);

    TextView heading = label("یاسر دیاگ  |  VALEO PL4_S1C", 21, true);
    heading.setTextColor(TEAL);
    root.addView(heading);
    root.addView(label("پروفایل اختصاصی PL4 • STM32F103CB + MC33290 + ESP-01", 13, false));
    statusText = label("برای شروع، گوشی را به Wi-Fi دستگاه وصل کرده و گزینه ۱ را انتخاب کنید.", 14, false);
    statusText.setTextColor(Color.rgb(247,212,160));
    root.addView(statusText);

    root.addView(action("۱  |  اتصال به ECU", () -> connectPl4()));
    root.addView(action("۲  |  شروع / توقف پارامترهای زنده", () -> toggleLive()));
    root.addView(action("۳  |  خواندن و ترجمه خطاها", () -> readFaults()));
    root.addView(action("۴  |  پاک‌کردن خطاها", () -> confirmErase()));
    root.addView(action("۵  |  عملگرها", () -> {
      if (!connectedToEcu) { status("ابتدا با گزینه ۱ به ECU متصل شوید"); return; }
      liveRequested=false;
      send("!5");
      actuatorRows.setVisibility(actuatorRows.getVisibility()==View.VISIBLE ? View.GONE : View.VISIBLE);
    }));

    TextView liveHeading = label("پارامترهای زنده PL4", 18, true);
    liveHeading.setTextColor(TEAL);
    root.addView(liveHeading);
    liveRows = column();
    root.addView(liveRows);
    root.addView(action("نمایش منوی PL4 / بستن پارامترها", () -> {
      if (liveRequested) { send("!2"); liveRequested=false; }
      status("نمایش پارامترهای زنده متوقف شد");
    }));

    TextView dtcHeading = label("کدهای خطا و وضعیت پاک‌سازی", 18, true);
    dtcHeading.setTextColor(TEAL);
    root.addView(dtcHeading);
    dtcText = label("هنوز خطایی خوانده نشده است.", 13, false);
    root.addView(dtcText);

    actuatorRows = column();
    actuatorRows.setVisibility(View.GONE);
    TextView actHeading = label("۱۱ تست عملگر PL4 • انتخاب مستقیم", 18, true);
    actHeading.setTextColor(TEAL);
    actuatorRows.addView(actHeading);
    actuatorRows.addView(label("نیازی به Y نیست. هر عملگر با تأیید شما اجرا می‌شود؛ موتور باید خاموش باشد.", 13, false));
    final String[] names = {
      "انژکتور ۱","انژکتور ۲","انژکتور ۳","انژکتور ۴",
      "رله پمپ بنزین","استپر موتور","کوئل ۱ و ۴","کوئل ۲ و ۳",
      "فن دور تند","فن دور کند","شیر کنیستر"
    };
    for (int i=0; i<names.length; i++) {
      final char key = (char)('A'+i);
      final int index = i;
      final String name = names[i];
      actuatorRows.addView(action(key+"  |  "+name, () -> confirmActuator(name,key,
          index<=4 || index==6 || index==7)));
    }
    actuatorRows.addView(action("لغو فرمان / بازگشت", () -> send("!X")));
    root.addView(actuatorRows);
    root.addView(action("بازگشت به انتخاب ECU", this::finish));
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    return l;
  }
  private int dp(int value) {
    return (int)(getResources().getDisplayMetrics().density*value+0.5f);
  }
  private TextView label(String text,int size,boolean bold) {
    TextView t=new TextView(this);
    t.setText(text); t.setTextSize(size); t.setTextColor(WHITE);
    t.setPadding(dp(9),dp(10),dp(9),dp(10));
    t.setGravity(Gravity.RIGHT); t.setTextDirection(View.TEXT_DIRECTION_RTL);
    if(bold)t.setTypeface(null,Typeface.BOLD);
    return t;
  }
  private Button action(String title,Runnable onClick) {
    Button b=new Button(this);
    b.setText(title); b.setTextSize(15);
    b.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
    b.setTextColor(WHITE); b.setAllCaps(false);
    GradientDrawable shape=new GradientDrawable();
    shape.setColor(CARD); shape.setCornerRadius(dp(7));
    shape.setStroke(dp(1),Color.rgb(37,101,114));
    b.setBackground(shape);
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(55));
    p.setMargins(0,dp(3),0,dp(3)); b.setLayoutParams(p);
    b.setOnClickListener(v -> onClick.run());
    return b;
  }
  private void status(String message) {
    runOnUiThread(() -> { if(!isFinishing() && statusText!=null) statusText.setText(message); });
  }
  private Socket wifiSocket() throws IOException {
    ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
    if(cm==null)throw new IOException("سرویس Wi-Fi در دسترس نیست");
    for(Network net:cm.getAllNetworks()) {
      NetworkCapabilities caps=cm.getNetworkCapabilities(net);
      if(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        return net.getSocketFactory().createSocket();
    }
    throw new IOException("ابتدا به شبکه Wi-Fi دستگاه ESP-01 متصل شوید");
  }
  private void writeLine(String line) throws IOException {
    synchronized(writeLock) {
      Socket s=socket;
      if(s==null||s.isClosed()||output==null)throw new IOException("اتصال TCP قطع است");
      output.write((line+"\n").getBytes("US-ASCII"));
      output.flush();
    }
  }
  private void send(String message) {
    new Thread(() -> {
      try {
        writeLine(message);
        status("فرمان "+message+" ارسال شد؛ منتظر پاسخ ECU");
      } catch(Exception ex) {
        status("فرمان ارسال نشد: "+ex.getMessage());
      }
    },"pl4-command").start();
  }
  private void connectPl4() {
    if(socket!=null && !socket.isClosed()) {
      send("!PROFILE:PL4");
      new Thread(() -> {
        try { Thread.sleep(220); writeLine("!1"); }
        catch(Exception e){ status("خطای اتصال ECU: "+e.getMessage()); }
      },"pl4-reconnect").start();
      return;
    }
    final int epoch=++socketEpoch;
    connectedToEcu=false;
    liveRequested=false;
    status("در حال اتصال به Wi-Fi دستگاه و سپس ECU...");
    new Thread(() -> {
      Socket s=null;
      try {
        s=wifiSocket();
        s.connect(new InetSocketAddress(HOST,PORT),4500);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        synchronized(writeLock) {
          if(epoch!=socketEpoch)throw new IOException("درخواست اتصال لغو شد");
          socket=s;
          output=s.getOutputStream();
        }
        startReader(s,epoch);
        writeLine("!PROFILE:PL4");
        Thread.sleep(220);
        writeLine("!1");
        status("درخواست اتصال به PL4 ارسال شد؛ منتظر پاسخ C1");
      } catch(Exception e) {
        status("اتصال برقرار نشد: "+e.getMessage());
        if(s!=null)try{s.close();}catch(Exception ignored){}
      }
    },"pl4-connect").start();
  }
  private void startReader(Socket s,int epoch) {
    new Thread(() -> {
      try(BufferedReader input=new BufferedReader(new InputStreamReader(s.getInputStream(),"UTF-8"))) {
        String line;
        while(epoch==socketEpoch && (line=input.readLine())!=null) {
          if(line.length()>12000)continue;
          final String packet=line;
          runOnUiThread(() -> { if(epoch==socketEpoch && !isFinishing()) parse(packet); });
        }
      } catch(Exception e) {
        if(epoch==socketEpoch)status("دریافت از دستگاه قطع شد: "+e.getMessage());
      } finally {
        if(epoch==socketEpoch) {
          connectedToEcu=false; liveRequested=false;
          status("اتصال TCP قطع شد؛ برای اتصال دوباره گزینه ۱ را بزنید");
          synchronized(writeLock) { if(socket==s){socket=null;output=null;} }
          try{s.close();}catch(Exception ignored){}
        }
      }
    },"pl4-receiver").start();
  }

  private void toggleLive() {
    if(!connectedToEcu){status("ابتدا گزینه ۱ اتصال ECU را اجرا کنید");return;}
    liveRequested=!liveRequested;
    send("!2");
    status(liveRequested?"در حال درخواست پارامترهای زنده":"درخواست توقف پارامترهای زنده ارسال شد");
  }
  private void readFaults() {
    if(!connectedToEcu){status("ابتدا به PL4 وصل شوید");return;}
    liveRequested=false;
    faultReport.setLength(0);
    dtcText.setText("در حال خواندن خطاها...");
    send("!3");
  }
  private void confirmErase() {
    if(!connectedToEcu){status("ابتدا به PL4 وصل شوید");return;}
    new AlertDialog.Builder(this)
      .setTitle("تأیید پاک‌کردن خطاهای PL4")
      .setMessage("فقط روی همان VALEO PL4_S1C آزمایش‌شده اجرا شود. موتور خاموش، سوئیچ باز و تغذیه پایدار باشد.\n\nاین عملیات از توالی اختصاصی V19.5 شامل مرحله حساس سرویس 0x34 استفاده می‌کند. خطاهای ذخیره‌شده از بین می‌روند. ادامه می‌دهید؟")
      .setNegativeButton("انصراف",null)
      .setPositiveButton("پاک‌کردن خطاها",(d,w)->{
        liveRequested=false;
        send("!4");
      }).show();
  }
  private void confirmActuator(String name,char key,boolean highRisk) {
    if(!connectedToEcu){status("ابتدا به PL4 متصل شوید");return;}
    String msg="موتور خاموش و محیط ایمن باشد. تست کوتاه و توقف آن در STM32 مدیریت می‌شود؛ پذیرش فرمان به معنی حرکت فیزیکی نیست.";
    if(highRisk)msg+="\nهشدار: کوئل، انژکتور و پمپ می‌توانند موجب جرقه یا پاشش سوخت شوند.";
    new AlertDialog.Builder(this)
      .setTitle("اجرای "+name)
      .setMessage(msg)
      .setNegativeButton("انصراف",null)
      .setPositiveButton("اجرای یک تست",(d,w)->{
        liveRequested=false;
        send("!"+key);
      }).show();
  }
  private void addLive(JSONArray params) {
    if(params==null)return;
    for(int i=0;i<params.length();i++) {
      JSONObject p=params.optJSONObject(i);
      if(p==null)continue;
      String id=p.optString("id","");
      if(!id.startsWith("pl4_"))continue;
      TextView value=liveValues.get(id);
      if(value==null) {
        LinearLayout row=column();
        row.setPadding(dp(4),dp(3),dp(4),dp(3));
        row.setBackgroundColor((liveValues.size()&1)==0?CARD:Color.rgb(19,47,68));
        row.addView(label(Pl4ParameterNames.name(id),14,true));
        value=label("—",16,false);
        value.setTextColor(TEAL);
        row.addView(value);
        liveRows.addView(row);
        liveValues.put(id,value);
      }
      String txt=p.isNull("value")?"—":p.optString("value","—");
      String unit=p.optString("unit","");
      value.setText(txt+(unit.isEmpty()?"":" "+unit));
    }
  }
  private void appendDtc(JSONObject packet) {
    String bank=packet.optString("bank","PL4");
    int count=packet.optInt("count",0);
    faultReport.append("گروه ").append(bank).append(" • تعداد اعلام‌شده: ").append(count).append("\n");
    JSONArray codes=packet.optJSONArray("codes");
    if(codes==null||codes.length()==0)faultReport.append("خطایی در این گروه ثبت نشده است.\n");
    else for(int i=0;i<codes.length();i++) {
      JSONObject code=codes.optJSONObject(i);
      if(code==null)continue;
      String raw=code.optString("code","");
      faultReport.append(Pl4DtcDescriptions.obdCode(raw))
        .append("  |  خام: ").append(raw)
        .append("  |  وضعیت: ").append(code.optString("status","—"))
        .append("\n").append(Pl4DtcDescriptions.describe(raw)).append("\n\n");
    }
    faultReport.append("\n");
    dtcText.setText(faultReport.toString());
  }
  private void parse(String line) {
    try {
      JSONObject packet=new JSONObject(line);
      String type=packet.optString("type","");
      if("parameters".equals(type) && "valeo_pl4_s1c".equals(packet.optString("profile"))) {
        addLive(packet.optJSONArray("parameters")); return;
      }
      if("dtc".equals(type) && "valeo_pl4_s1c".equals(packet.optString("profile"))) {
        appendDtc(packet); return;
      }
      if(!"status".equals(type))return;
      String msg=packet.optString("message","");
      if("PL4_ECU_CONNECTED".equals(msg)) {
        connectedToEcu=true;
        status("ارتباط ECU PL4 تأیید شد؛ پارامترها، خطاها و عملگرها آماده‌اند");
      } else if("PL4_ECU_CONNECTION_FAILED".equals(msg)) {
        connectedToEcu=false; status("ECU پاسخ معتبر C1 نداد");
      } else if("PROFILE_PL4_SELECTED".equals(msg)) {
        status("پروفایل PL4 فعال شد؛ در حال شروع ارتباط");
      } else if("PROFILE_UNSUPPORTED_IN_PL4_FIRMWARE".equals(msg)) {
        status("فریم‌ور PL4 فقط مخصوص همین ECU است");
      } else if("PL4_DTC_READING".equals(msg)) {
        faultReport.setLength(0); dtcText.setText("در حال دریافت دو گروه خطا...");
      } else if("PL4_DTC_READ_OK".equals(msg)) {
        status("خواندن خطاها تکمیل شد؛ متن و کد خام نمایش داده شده");
      } else if("PL4_DTC_READ_FAILED".equals(msg)) {
        status("دریافت خطا تأیید نشد؛ کد صفر فرض نکنید");
      } else if("PL4_DTC_CLEARING".equals(msg)) {
        status("در حال پاک‌کردن خطاها؛ منتظر پاسخ ECU");
      } else if("PL4_DTC_CLEAR_ACCEPTED_RECHECKING".equals(msg)) {
        status("پاسخ 54 FF 00 تأیید شد؛ در حال خواندن مجدد خطاها");
      } else if("PL4_DTC_CLEAR_ABORTED".equals(msg)||"PL4_DTC_CLEAR_NOT_CONFIRMED".equals(msg)) {
        status("پاک‌کردن خطا تأیید نشد؛ نتیجه موفق اعلام نمی‌شود");
      } else if("PL4_LIVE_ON".equals(msg)) {
        liveRequested=true; status("دریافت پارامترهای PL4 فعال شد");
      } else if("PL4_LIVE_OFF".equals(msg)) {
        liveRequested=false; status("دریافت پارامترهای PL4 متوقف شد");
      } else if("PL4_ACTUATOR_TEST_FINISHED".equals(msg)) {
        status("تست عملگر پایان یافت؛ حرکت فیزیکی باید جداگانه بررسی شود");
      } else if("PL4_NOT_CONNECTED".equals(msg)) {
        connectedToEcu=false; status("ابتدا گزینه ۱ را بزنید");
      } else if("PL4_BRIDGE_READY".equals(msg)) {
        status("پل ESP-01 آماده دریافت فرمان است");
      } else if(msg.startsWith("PL4_")) {
        status("وضعیت PL4: "+msg);
      }
    } catch(Exception ignored) {
      // The ESP bridge may forward non-JSON console text; ignore it safely.
    }
  }
  private void closeBridge() {
    socketEpoch++;
    connectedToEcu=false; liveRequested=false;
    synchronized(writeLock) {
      try{if(socket!=null)socket.close();}catch(Exception ignored){}
      socket=null;output=null;
    }
  }
  @Override protected void onDestroy() {
    closeBridge();
    super.onDestroy();
  }
}
