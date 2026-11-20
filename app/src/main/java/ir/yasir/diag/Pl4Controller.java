package ir.yasir.diag;

import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONArray;
 import java.util.LinkedHashMap;
import java.util.Map;

/** PL4 UI and state machine. All traffic uses MainActivity's existing TCP socket. */
final class Pl4Controller {
  final MainActivity a;
  final LinearLayout connecting, menu, live, faults, actuators;
  final TextView connectionStatus, menuStatus, liveStatus, faultStatus, actuatorStatus, faultText;
  final Map<String,TextView> values=new LinkedHashMap<>();
  final StringBuilder report=new StringBuilder();
  final TextView wireTrace;
  boolean crlf=false, pingWaiting=false;
  int pingSequence=0;
  volatile boolean selected=false;
  volatile boolean legacyBlocked=false;
  volatile int generation=0;
  boolean ready=false, liveOn=false, busy=false, eraseAccepted=false;
  int phase=0, operation=0;
  static final String[] IDS={
    "pl4_rpm","pl4_battery","pl4_coolant","pl4_intake","pl4_tps_voltage","pl4_throttle_angle",
    "pl4_map","pl4_speed","pl4_injection_time","pl4_advance","pl4_stepper","pl4_dwell14","pl4_dwell23",
    "pl4_o2_front","pl4_o2_rear","pl4_fuel_cut_raw","pl4_throttle_state_raw","pl4_canister_duty",
    "pl4_canister_state_raw","pl4_ac_request_raw","pl4_ac_pressure","pl4_intake_thermostat_raw",
    "pl4_coolant_alt_b11","pl4_intake_alt_b12","pl4_fuel_reg_raw","pl4_fan_relay_raw",
    "pl4_fan_actual","pl4_fan_target","pl4_torque","pl4_gear_raw","pl4_power_relay_raw",
    "pl4_lock_raw","pl4_immo_raw","pl4_unlock_fault_raw","pl4_distance","pl4_speed_disconnects","pl4_speed_error_time"
  };
  Pl4Controller(MainActivity activity) {
    a=activity;
    LinearLayout old=a.content;
    connecting=screen("VALEO PL4_S1C","اتصال مستقیم K-Line");
    connectionStatus=statusField("در حال برقراری ارتباط...");
    wireTrace=statusField("TX —   |   RX —");
    wireTrace.setTextSize(12);
    row(connecting,"تلاش مجدد","ECU",this::select);
    row(connecting,"آزمایش پل STM32 / ESP","LINK",this::ping);
    row(connecting,"بازگشت به انتخاب ECU","‹",this::catalog);

    menu=screen("VALEO PL4_S1C","منوی عیب‌یابی موتور");
    menuStatus=statusField("ECU متصل شد");
    row(menu,"پارامترهای زنده","▤",this::openLive);
    row(menu,"خواندن خطاها","DTC",this::readFaults);
    row(menu,"پاک کردن خطاها","CLR",this::confirmErase);
    row(menu,"عملگرها","ACT",this::openActuators);
    row(menu,"بازگشت به انتخاب ECU","‹",this::catalog);

    live=screen("پارامترهای زنده","VALEO PL4_S1C");
    liveStatus=statusField("در انتظار داده معتبر");
    for(String id:IDS)values.put(id,a.tableRow(Pl4ParameterNames.name(id)));
    row(live,"بازگشت به منوی ECU","‹",this::back);

    faults=screen("خطاهای ECU","VALEO PL4_S1C • شرح فارسی");
    faultStatus=statusField("هنوز کدی خوانده نشده");
    row(faults,"خواندن مجدد خطاها","DTC",this::readFaults);
    row(faults,"پاک کردن خطاها","CLR",this::confirmErase);
    faultText=a.field("کد خام، وضعیت و شرح فارسی");
    faultText.setTextSize(14);
    row(faults,"بازگشت به منوی ECU","‹",this::back);

    actuators=screen("عملگرها","VALEO PL4_S1C • موتور خاموش");
    actuatorStatus=statusField("تست فقط در شرایط تعمیرگاهی ایمن");
    String[] names={"انژکتور ۱","انژکتور ۲","انژکتور ۳","انژکتور ۴","رله پمپ بنزین",
      "استپر موتور","کوئل ۱ و ۴","کوئل ۲ و ۳","فن دور تند","فن دور کند","شیر کنیستر"};
    for(int i=0;i<names.length;i++){
      final int k=i;
      row(actuators,names[i],String.valueOf((char)('A'+i)),()->confirmActuator(names[k],(char)('A'+k),k<5||k==6||k==7));
    }
    row(actuators,"درخواست توقف تست","STOP",()->{if(ready&&busy)write("!X");});
    row(actuators,"بازگشت به منوی ECU","‹",this::back);
    a.content=old;
  }
  LinearLayout screen(String title,String sub){
    LinearLayout x=a.section("");
    a.content=x; a.styleBlueWorkshopPage(x); a.blueWorkshopHeader(title,sub); return x;
  }
  TextView statusField(String message){
    TextView t=a.text(message,14,true);t.setTextColor(Color.rgb(185,224,255));
    t.setGravity(android.view.Gravity.CENTER);a.content.addView(t);return t;
  }
  // Dedicated high-contrast scanner menu; the theme must not recolor the icon.
  void row(LinearLayout p,String name,String icon,Runnable action){
    LinearLayout r=new LinearLayout(a); r.setTag("pl4-fixed-theme");
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
    r.setGravity(android.view.Gravity.CENTER_VERTICAL);
    r.setPadding(a.dp(10),a.dp(7),a.dp(10),a.dp(7));
    r.setBackground(a.panelDrawable(Color.rgb(18,44,65),Color.rgb(43,78,100),7));
    TextView badge=a.text(icon,icon.length()>3?10:15,true);
    badge.setGravity(android.view.Gravity.CENTER);badge.setTextColor(Color.rgb(100,226,212));
    badge.setBackground(a.panelDrawable(Color.rgb(9,28,43),Color.rgb(56,96,111),5));
    r.addView(badge,new LinearLayout.LayoutParams(a.dp(52),a.dp(44)));
    TextView title=a.text(name,16,true);
    title.setGravity(android.view.Gravity.CENTER_VERTICAL|android.view.Gravity.RIGHT);
    title.setTextColor(Color.rgb(240,247,251));
    r.addView(title,new LinearLayout.LayoutParams(0,a.dp(44),1));
    TextView arrow=a.text("‹",22,true);arrow.setTextColor(Color.rgb(128,174,191));
    r.addView(arrow,new LinearLayout.LayoutParams(a.dp(24),a.dp(44)));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,a.dp(66));
    lp.setMargins(0,0,0,a.dp(7));p.addView(r,lp);
    r.setOnClickListener(v->action.run());
  }
  boolean owns(LinearLayout x){return x!=null&&(x==connecting||x==menu||x==live||x==faults||x==actuators);}
  String key(LinearLayout x){
    if(x==connecting)return "pl4Connecting";if(x==menu)return "pl4Menu";if(x==live)return "pl4Live";
    if(x==faults)return "pl4Faults";if(x==actuators)return "pl4Actuators";return "";
  }
  void status(String msg){
    a.setStatus(msg);
    for(TextView t:new TextView[]{connectionStatus,menuStatus,liveStatus,faultStatus,actuatorStatus})t.setText(msg);
  }
  void reset(){generation++;phase=0;ready=false;liveOn=false;busy=false;pingWaiting=false;pingSequence++;crlf=false;operation++;}
  void select(){
    if(selected&&(busy||phase!=0)){status("منتظر پایان اتصال یا عملیات جاری بمانید");return;}
    reset();selected=true;legacyBlocked=true;
    a.selectedEcu="والئو PL4_S1C";a.ecuActionsShownForCurrentConnection=false;
    a.autoDetectionActive=false;a.autoDetectionToken++;
    a.pendingProfileCommand="";a.pendingPostConnectCommand="";a.scanAfterTcpConnect=false;
    a.preferences.edit().putString("yasir.lastEcu",a.selectedEcu).apply();
    for(TextView t:values.values())t.setText("—");
    report.setLength(0);faultText.setText("—");
    a.show(connecting);wireTrace.setText("TX —  |  RX —");status("در حال اتصال مستقیم به والئو PL4...");
    if(a.tcpLive&&a.socket!=null&&!a.socket.isClosed())begin();
    else if(!a.running)a.connect();
    else status("در انتظار اتصال TCP به دستگاه...");
  }
  void begin(){
    if(!selected||!a.tcpLive||phase!=0||ready)return;
    phase=1;crlf=false;
    status("TCP متصل • انتخاب پروفایل والئو");
    write("!PROFILE:PL4");
    final int g=generation;
    // Try the alternate newline only for a profile selection, never for
    // erase/actuator commands, and never connect before profile ACK.
    a.ui.postDelayed(()->{
      if(selected&&generation==g&&phase==1&&a.tcpLive){
        crlf=true;status("در انتظار پاسخ STM32؛ ارسال با پایان CRLF");
        write("!PROFILE:PL4");
      }
    },3000);
    a.ui.postDelayed(()->{
      if(selected&&generation==g&&phase==1){
        phase=0;status("پاسخ انتخاب پروفایل از STM32 نرسید. گزارش TX/RX را بررسی کنید.");
      }
    },8000);
  }
  void ping(){
    if(!a.tcpLive){status("TCP قطع است؛ ابتدا اتصال Wi-Fi دستگاه را بررسی کنید");return;}
    if(busy){status("منتظر پایان عملیات جاری بمانید");return;}
    if(pingWaiting)return;
    final int g=generation,p=++pingSequence;
    pingWaiting=true;
    status("آزمایش UART • ارسال !PING");
    write("!PING",false);
    a.ui.postDelayed(()->{if(selected&&generation==g&&pingWaiting&&pingSequence==p){
      write("!PING",true);
    }},2000);
    a.ui.postDelayed(()->{if(selected&&generation==g&&pingWaiting&&pingSequence==p){
      pingWaiting=false;status("پاسخ PING از STM32 دریافت نشد؛ TX فقط تأیید نوشتن در سوکت است.");
    }},5000);
  }
  void tcpLost(){tcpLost("اتصال TCP از سمت دستگاه بسته شد");}
  void tcpLost(String detail){
    if(!selected)return;
    reset();status("TCP قطع • "+detail+"؛ تلاش مجدد...");a.show(connecting);
  }
  void write(String command){write(command,crlf);}
  void write(String command,boolean ending){
    final int g=generation;
    a.queueWire(command,true,g,ending,(bytes,error)->{
      if(!selected||generation!=g)return;
      if(error!=null){
        wireTrace.setText("TX ERROR: "+command+" | "+error);
        reset();status("فرمان ارسال نشد: "+error);a.show(connecting);
      }else{
        wireTrace.setText("TX "+command+" | "+bytes+" bytes | "+(ending?"CRLF":"LF")+"\nدر انتظار RX");
      }
    });
  }
  boolean canOperate(){
    if(!ready||!a.tcpLive){status("ابتدا اتصال معتبر ECU لازم است");return false;}
    if(busy){status("عملیات قبلی هنوز تمام نشده است");return false;}return true;
  }
  void op(String cmd,String msg,int timeout){
    busy=true;int token=++operation;status(msg);write(cmd);
    a.ui.postDelayed(()->{if(selected&&busy&&operation==token){
      reset();status("پاسخ عملیات تأیید نشد؛ دوباره ECU را متصل کنید. اگر تست عملگر بود، سوئیچ را خاموش کنید.");
      a.show(connecting);
    }},timeout);
  }
  void done(){busy=false;operation++;}
  void openLive(){
    if(!canOperate())return;a.show(live);
    for(TextView t:values.values())t.setText("—");
    if(!liveOn)op("!2","در حال دریافت پارامترها...",9000);
  }
  void readFaults(){
    if(!canOperate())return;liveOn=false;eraseAccepted=false;
    report.setLength(0);faultText.setText("در انتظار پاسخ...");
    a.show(faults);op("!3","در حال خواندن خطاها...",16000);
  }
  void confirmErase(){
    if(!canOperate())return;
    new AlertDialog.Builder(a).setTitle("پاک کردن خطاهای PL4")
      .setMessage("موتور خاموش، سوئیچ باز و تغذیه پایدار باشد. فقط روی PL4_S1C تأییدشده. این عملیات کدهای ذخیره‌شده را حذف می‌کند و شامل مرحله حساس حافظه در توالی V19.5 است.")
      .setNegativeButton("انصراف",null).setPositiveButton("پاک کردن",(d,w)->{
        if(!selected||!canOperate())return;
        liveOn=false;eraseAccepted=false;report.setLength(0);faultText.setText("منتظر تأیید ECU...");
        a.show(faults);op("!4","در حال پاک کردن خطاها...",30000);
      }).show();
  }
  void openActuators(){
    if(!canOperate())return;liveOn=false;a.show(actuators);
    op("!5","در حال آماده‌سازی منوی عملگرها...",9000);
  }
  void confirmActuator(String name,char key,boolean risk){
    if(!canOperate())return;
    String msg="خودرو ثابت، موتور خاموش و محیط تعمیرگاهی ایمن باشد. توقف خودکار با STM32 انجام می‌شود.";
    if(risk)msg+="\nهشدار: امکان جرقه یا پاشش سوخت.";
    new AlertDialog.Builder(a).setTitle("تست "+name).setMessage(msg)
      .setNegativeButton("انصراف",null).setPositiveButton("اجرای کوتاه",(d,w)->{
        if(selected&&a.currentScreen==actuators&&canOperate())op("!"+key,"در حال تست "+name+"...",15000);
      }).show();
  }
  void back(){
    if(busy){status("منتظر پایان عملیات جاری بمانید");return;}
    if(!ready){a.show(connecting);return;}
    if(liveOn)op("!X","در حال توقف دریافت پارامترها...",9000);
    a.show(menu);
  }
  void catalog(){
    if(busy){status("منتظر پایان عملیات جاری بمانید");return;}
    leave();a.show(a.ecuSelectionSection);
  }
  void leave(){
    if(!selected)return;
    selected=false;reset();
    if(a.selectedEcu!=null&&a.selectedEcu.startsWith("والئو"))a.selectedEcu="";
  }
  boolean packet(JSONObject j){
    String m=j.optString("message",""),type=j.optString("type","");
    if(!selected && (m.equals("PROFILE_SIEMENS_SELECTED") || m.equals("PROFILE_BOSCH_SELECTED") || m.equals("PROFILE_SB13_CAN_SELECTED"))) legacyBlocked=false;
    boolean profileAck="ack".equals(type)&&"PROFILE:PL4".equals(j.optString("command"));
    boolean ours="valeo_pl4_s1c".equals(j.optString("profile"))||m.startsWith("PL4_")||m.equals("PROFILE_PL4_SELECTED")||m.equals("STM32_V54_READY")||profileAck;
    if(!ours)return false;if(!selected)return true;
    if(!m.isEmpty()&&!type.equals("parameters"))wireTrace.setText("RX: "+m);
    if(m.equals("STM32_V54_READY")){
      pingWaiting=false;status("پاسخ STM32 دریافت شد • ارتباط UART تأیید شد");
    }else if((m.equals("PROFILE_PL4_SELECTED")||profileAck)&&phase==1){
      phase=2;status("پروفایل تأیید شد؛ اتصال مستقیم ECU...");
      final int g=generation;
      a.ui.postDelayed(()->{if(selected&&generation==g&&phase==2)write("!1");},220);
      a.ui.postDelayed(()->{if(selected&&generation==g&&phase==2){phase=0;status("STM32 پاسخ داد اما ECU پاسخ اتصال نداد؛ سوئیچ و K-Line را بررسی کنید.");}},12000);
    }else if(m.equals("PL4_ECU_CONNECTED")&&phase==2&&a.tcpLive){
      phase=0;ready=true;busy=false;liveOn=false;status("ECU متصل شد • PL4_S1C");a.show(menu);
    }else if(m.equals("PL4_ECU_CONNECTION_FAILED")||m.equals("PL4_NOT_CONNECTED")||m.equals("PL4_ECU_DISCONNECTED")){
      reset();status("TCP برقرار است، اما ECU پاسخ معتبر C1 نداد؛ سوئیچ، تغذیه و K-Line را بررسی کنید");a.show(connecting);
    }else if(type.equals("parameters")&&ready&&liveOn){
      JSONArray p=j.optJSONArray("parameters");
      if(p!=null)for(int i=0;i<p.length();i++){
        JSONObject x=p.optJSONObject(i);if(x==null)continue;
        TextView v=values.get(x.optString("id"));if(v==null)continue;
        String unit=x.optString("unit","");
        v.setText(x.optString("value","—")+(unit.isEmpty()?"":" "+unit));
      }
      if(a.currentScreen==live)liveStatus.setText("آخرین دریافت: "+new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date()));
    }else if(type.equals("dtc")&&ready){
      JSONArray codes=j.optJSONArray("codes");int count=j.optInt("count",0),got=codes==null?0:codes.length();
      report.append("گروه ").append(j.optString("bank","PL4")).append(" | تعداد: ").append(count).append("\n");
      if(count==0&&got==0)report.append("خطایی در این گروه گزارش نشد.\n");
      else if(got!=count)report.append("هشدار: پاسخ ناقص؛ ").append(got).append(" کد دریافت شد.\n");
      if(codes!=null)for(int i=0;i<codes.length();i++){
        JSONObject x=codes.optJSONObject(i);if(x==null)continue;String raw=x.optString("code","");
        report.append(Pl4DtcDescriptions.obdCode(raw)).append(" | خام: ").append(raw)
          .append(" | وضعیت: ").append(x.optString("status","—")).append("\n")
          .append(Pl4DtcDescriptions.describe(raw)).append("\n\n");
      }
      faultText.setText(report.toString());
    }else if(m.equals("PL4_LIVE_ON")){
      liveOn=true;done();status("دریافت پارامترها فعال شد");
    }else if(m.equals("PL4_LIVE_OFF")){
      liveOn=false;done();status("آماده عملیات");
    }else if(m.equals("PL4_DTC_READING")){
      report.setLength(0);faultText.setText("در حال دریافت خطاهای ECU...");
    }else if(m.equals("PL4_DTC_CLEAR_ACCEPTED_RECHECKING")){
      eraseAccepted=true;status("پاک‌سازی توسط ECU پذیرفته شد؛ خواندن مجدد...");
    }else if(m.equals("PL4_DTC_READ_OK")||m.equals("PL4_DTC_READ_PARTIAL")||m.equals("PL4_DTC_READ_FAILED")){
      done();
      String result=m.equals("PL4_DTC_READ_OK")?"خواندن خطاها انجام شد":
        m.equals("PL4_DTC_READ_PARTIAL")?"یک بانک خطا پاسخ داد؛ نتیجه کامل نیست":"خواندن خطا تأیید نشد";
      status((eraseAccepted?"پاک‌سازی تأیید شد؛ ":"")+result);eraseAccepted=false;
      if(report.length()==0)faultText.setText("پاسخ کدها تأیید نشد");
    }else if(m.equals("PL4_DTC_CLEAR_ABORTED")||m.equals("PL4_DTC_CLEAR_NOT_CONFIRMED")){
      done();eraseAccepted=false;status("پاک کردن خطا تأیید نشد");
    }else if(m.equals("PL4_ACTUATOR_OFF_NOT_CONFIRMED_SWITCH_IGNITION_OFF")){
      reset();status("هشدار: توقف عملگر تأیید نشد؛ فوراً سوئیچ را خاموش کنید");a.show(connecting);
    }else if(m.equals("PL4_ACTUATOR_TEST_FINISHED")){
      done();status("تست پایان یافت؛ پاسخ توقف ECU دریافت شد. حرکت فیزیکی را بررسی کنید.");
    }else if(m.equals("PL4_ACTUATOR_ON_NOT_CONFIRMED_OFF_CONFIRMED")||m.equals("PL4_ACTUATOR_BLOCKED_ENGINE")||
      m.equals("PL4_ACTUATOR_COOLDOWN")||m.equals("PL4_INVALID_COMMAND")){
      done();status(m.equals("PL4_ACTUATOR_BLOCKED_ENGINE")?"خاموش بودن موتور تأیید نشد":
        m.equals("PL4_ACTUATOR_COOLDOWN")?"فاصله ایمن تست هنوز تمام نشده":"درخواست عملگر تأیید نشد");
    }
    return true;
  }
  boolean allowsLegacy(String cmd){return !selected && (!legacyBlocked || cmd.startsWith("!PROFILE:"));}
  void shutdown(){selected=false;reset();}
}
