package ir.yasir.diag;

import java.util.HashMap;
import java.util.Map;

/** Human labels for the verified PL4 V19.5 / Sicar live-data field IDs. */
public final class Pl4ParameterNames {
  private static final Map<String, String> LABELS = new HashMap<>();
  static {
    LABELS.put("pl4_rpm", "دور موتور");
    LABELS.put("pl4_battery", "ولتاژ باتری");
    LABELS.put("pl4_injection_time", "زمان پاشش");
    LABELS.put("pl4_fuel_cut_raw", "وضعیت قطع پاشش (خام)");
    LABELS.put("pl4_coolant", "دمای آب موتور");
    LABELS.put("pl4_tps_voltage", "ولتاژ دریچه گاز");
    LABELS.put("pl4_throttle_state_raw", "وضعیت دریچه گاز (خام)");
    LABELS.put("pl4_throttle_angle", "زاویه دریچه گاز");
    LABELS.put("pl4_intake", "دمای هوای ورودی");
    LABELS.put("pl4_map", "فشار منیفولد");
    LABELS.put("pl4_stepper", "موقعیت استپر");
    LABELS.put("pl4_canister_state_raw", "وضعیت کنیستر (خام)");
    LABELS.put("pl4_canister_duty", "درصد فرمان کنیستر");
    LABELS.put("pl4_ac_request_raw", "درخواست کولر (خام)");
    LABELS.put("pl4_advance", "آوانس جرقه");
    LABELS.put("pl4_dwell14", "زمان شارژ کوئل ۱ و ۴");
    LABELS.put("pl4_dwell23", "زمان شارژ کوئل ۲ و ۳");
    LABELS.put("pl4_o2_front", "ولتاژ اکسیژن اول");
    LABELS.put("pl4_o2_rear", "ولتاژ اکسیژن دوم");
    LABELS.put("pl4_fuel_reg_raw", "وضعیت تنظیم سوخت (خام)");
    LABELS.put("pl4_fan_relay_raw", "وضعیت رله فن (خام)");
    LABELS.put("pl4_fan_actual", "درصد واقعی فن");
    LABELS.put("pl4_fan_target", "درصد هدف فن");
    LABELS.put("pl4_torque", "بار/گشتاور موتور");
    LABELS.put("pl4_speed", "سرعت خودرو");
    LABELS.put("pl4_gear_raw", "وضعیت دنده (خام)");
    LABELS.put("pl4_power_relay_raw", "رله تغذیه (خام)");
    LABELS.put("pl4_lock_raw", "قفل مرکزی (خام)");
    LABELS.put("pl4_immo_raw", "ایموبلایزر (خام)");
    LABELS.put("pl4_ac_pressure", "فشار سامانه کولر");
    LABELS.put("pl4_intake_thermostat_raw", "ترموستات هوای ورودی (خام)");
    LABELS.put("pl4_coolant_alt_b11", "دمای آب (فرمول جایگزین، نیازمند تطبیق)");
    LABELS.put("pl4_intake_alt_b12", "دمای هوا (فرمول جایگزین، نیازمند تطبیق)");
    LABELS.put("pl4_unlock_fault_raw", "وضعیت خطای بازگشایی (خام)");
    LABELS.put("pl4_distance", "مسافت طی‌شده");
    LABELS.put("pl4_speed_disconnects", "تعداد قطع سنسور سرعت");
    LABELS.put("pl4_speed_error_time", "زمان خطای سنسور سرعت");
  }
  private Pl4ParameterNames() {}
  public static String name(String id) {
    String result = LABELS.get(id);
    return result == null ? id : result;
  }
}
