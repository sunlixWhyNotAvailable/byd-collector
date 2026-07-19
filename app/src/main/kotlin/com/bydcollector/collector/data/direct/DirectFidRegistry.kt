package com.bydcollector.collector.data.direct

import java.util.Locale

data class DirectFidEntry(
    val key: String,
    val dev: Int,
    val fid: Int,
    val tx: Int,
    val decoder: DirectValueDecoder,
    val scale: Double = 0.0,
    val groupName: String = "autoservice",
    val featureNames: String = key,
    val classification: String = "direct_dynamic",
    val prodCategory: String = "unknown_keep_queryable",
    val source: String = "wide-poll-session-20260605_161751"
) {
    val sourceId: String = "autoservice:$dev:$fid:$tx"
    //keeps discovery provenance queryable in sqlite next to each raw field
    val note: String = "source=$source dev=$dev fid=$fid tx=$tx decoder=$decoder scale=$scale classification=$classification prod_category=$prodCategory feature_names=$featureNames"
}

enum class DirectValueDecoder {
    INT_RAW,
    INT_ENUM,
    INT_PERCENT,
    INT_TEMP_C,
    INT_TEMP_C_OFS40,
    INT_KPA,
    INT_SCALED,
    FLOAT_RAW,
    FLOAT_PERCENT,
    FLOAT_KWH,
    FLOAT_VOLT
}

//curated read-only autoservice scope for the main db; exploratory values belong in round-robin
object DirectFidRegistry {
    const val CATALOG_VERSION = "autoservice-fid-direct-20260719-curated-81-energy-soc-v1"
    const val TX_GET_INT = 5
    const val TX_GET_FLOAT = 7

    val entries: List<DirectFidEntry> = listOf(
        DirectFidEntry("statistic_1014_1145045040_5", 1014, 1145045040, TX_GET_INT, DirectValueDecoder.INT_PERCENT, groupName = "direct_statistic", featureNames = "STATISTIC_SOC_BATTERY_PERCENTAGE;Statistic.STATISTIC_SOC_BATTERY_PERCENTAGE", classification = "curated_debug_promotion", prodCategory = "curated_main", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_1014_1134559272_5", 1014, 1134559272, TX_GET_INT, DirectValueDecoder.INT_PERCENT, groupName = "direct_statistic", featureNames = "STATISTIC_ESTIMATE_SOC_V1;Statistic.STATISTIC_ESTIMATE_SOC_V1", classification = "round_robin_changing_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_remaining_battery_power", 1014, 1148190760, TX_GET_INT, DirectValueDecoder.INT_SCALED, scale = 0.1, groupName = "direct_statistic", featureNames = "STATISTIC_REMAINING_BATTERY_POWER;Statistic.STATISTIC_REMAINING_BATTERY_POWER", classification = "round_robin_promoted_20260719_energy_soc", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("power_battery_remain_electricity", 1005, 882901008, TX_GET_FLOAT, DirectValueDecoder.FLOAT_KWH, groupName = "direct_power", featureNames = "POWER_BATTERY_REMAIN_ELECTRICITY;Power.POWER_BATTERY_REMAIN_ELECTRICITY", classification = "round_robin_promoted_20260719_energy_soc", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_statistic_this_trip_total_elec_consumption", 1014, 1246801976, TX_GET_FLOAT, DirectValueDecoder.FLOAT_RAW, groupName = "direct_statistic", featureNames = "Statistic.STATISTIC_THIS_TRIP_TOTAL_ELEC_CONSUMPTION", classification = "round_robin_promoted_20260719_energy_soc", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_total_elec_consumption", 1014, 1032871984, TX_GET_FLOAT, DirectValueDecoder.FLOAT_RAW, groupName = "direct_statistic", featureNames = "STATISTIC_TOTAL_ELEC_CONSUMPTION;Statistic.STATISTIC_TOTAL_ELEC_CONSUMPTION", classification = "round_robin_promoted_20260719_energy_soc", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_1014_1145045032_5", 1014, 1145045032, TX_GET_INT, DirectValueDecoder.INT_PERCENT, groupName = "direct_statistic", featureNames = "STATISTIC_BATTERY_HEALTHY_INDEX;Statistic.STATISTIC_BATTERY_HEALTHY_INDEX", classification = "round_robin_promoted_20260619_soh", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("charging_charge_battery_volt", 1009, 1145045000, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_charging", featureNames = "CHARGING_CHARGE_BATTERY_VOLT;Charging.CHARGING_CHARGE_BATTERY_VOLT", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("charging_charge_current", 1009, 1145045016, TX_GET_FLOAT, DirectValueDecoder.FLOAT_RAW, groupName = "direct_charging", featureNames = "CHARGING_CHARGE_CURRENT;Charging.CHARGING_CHARGE_CURRENT", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ota_battery_voltage", 1032, 1150287896, TX_GET_FLOAT, DirectValueDecoder.FLOAT_VOLT, groupName = "direct_ota", featureNames = "OTA_BATTERY_VOLTAGE;Ota.OTA_BATTERY_VOLTAGE", classification = "diagnostic_candidate", prodCategory = "unknown_keep_queryable", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_highest_battery_voltage", 1014, 1147142192, TX_GET_INT, DirectValueDecoder.INT_SCALED, scale = 0.001, groupName = "direct_statistic", featureNames = "STATISTIC_HIGHEST_BATTERY_VOLTAGE;Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_lowest_battery_voltage", 1014, 1147142160, TX_GET_INT, DirectValueDecoder.INT_SCALED, scale = 0.001, groupName = "direct_statistic", featureNames = "STATISTIC_LOWEST_BATTERY_VOLTAGE;Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ac_ac_temp_inside", 1000, 1031798832, TX_GET_INT, DirectValueDecoder.INT_TEMP_C, groupName = "direct_ac", featureNames = "Ac.AC_TEMP_INSIDE", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ac_1000_1077936184_5", 1000, 1077936184, TX_GET_INT, DirectValueDecoder.INT_TEMP_C, groupName = "direct_ac", featureNames = "AC_TEMP_OUT;Ac.AC_TEMP_OUT", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_1014_1148190752_5", 1014, 1148190752, TX_GET_INT, DirectValueDecoder.INT_TEMP_C_OFS40, groupName = "direct_statistic", featureNames = "STATISTIC_HIGHEST_BATTERY_TEMP;Statistic.STATISTIC_HIGHEST_BATTERY_TEMP", classification = "round_robin_changing_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_1014_1148190736_5", 1014, 1148190736, TX_GET_INT, DirectValueDecoder.INT_TEMP_C_OFS40, groupName = "direct_statistic", featureNames = "STATISTIC_LOWEST_BATTERY_TEMP;Statistic.STATISTIC_LOWEST_BATTERY_TEMP", classification = "round_robin_changing_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("gb_1039_1154482192_5", 1039, 1154482192, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_gb", featureNames = "GB_FRONT_MOTOR_TEMP;Gb.GB_FRONT_MOTOR_TEMP", classification = "round_robin_changing_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("gb_trear_motor_temp", 1039, 1155530768, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_gb", featureNames = "GB_TREAR_MOTOR_TEMP;Gb.GB_TREAR_MOTOR_TEMP", classification = "diagnostic_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_elec_driving_range_yun", 1014, 1032863760, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_statistic", featureNames = "STATISTIC_ELEC_DRIVING_RANGE_YUN;Statistic.STATISTIC_ELEC_DRIVING_RANGE_YUN", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_1014_1246765072_5", 1014, 1246765072, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_statistic", featureNames = "STATISTIC_TOTAL_MILEAGE;Statistic.STATISTIC_TOTAL_MILEAGE", classification = "round_robin_changing_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("speed_1013_-1807745016_7", 1013, -1807745016, TX_GET_FLOAT, DirectValueDecoder.FLOAT_RAW, groupName = "direct_speed", featureNames = "SPEED_AUTO_SPEED;Speed.SPEED_AUTO_SPEED", classification = "round_robin_changing_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("charging_1009_1231032336_5", 1009, 1231032336, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_charging", featureNames = "CHARGING_STATE;Charging.CHARGING_STATE", classification = "curated_debug_promotion", prodCategory = "curated_main", source = "live_reflection_device_map"),
        DirectFidEntry("charging_1009_876609586_5", 1009, 876609586, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_charging", featureNames = "CHARGING_GUN_CONNECT_STATE;Charging.CHARGING_GUN_CONNECT_STATE", classification = "round_robin_promoted_20260613", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ac_1000_1077936144_5", 1000, 1077936144, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_ac", featureNames = "AC_POWER_STATE;Ac.AC_POWER_STATE", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ac_1000_1077936168_5", 1000, 1077936168, TX_GET_INT, DirectValueDecoder.INT_TEMP_C, groupName = "direct_ac", featureNames = "AC_TEMP_MAIN;Ac.AC_TEMP_MAIN", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ac_wind_level", 1000, 1077936156, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_ac", featureNames = "AC_WIND_LEVEL;Ac.AC_WIND_LEVEL", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ota_lf_door_lock", 1032, 1081081864, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_ota", featureNames = "OTA_LF_DOOR_LOCK;Ota.OTA_LF_DOOR_LOCK", classification = "diagnostic_candidate", prodCategory = "unknown_keep_queryable", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_rf_door_lock_status", 1001, 1081081866, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_RF_DOOR_LOCK_STATUS;Bodywork.BODYWORK_RF_DOOR_LOCK_STATUS", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_lr_door_lock_status", 1001, 1081081868, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_LR_DOOR_LOCK_STATUS;Bodywork.BODYWORK_LR_DOOR_LOCK_STATUS", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_rr_door_lock_status", 1001, 1081081870, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_RR_DOOR_LOCK_STATUS;Bodywork.BODYWORK_RR_DOOR_LOCK_STATUS", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_left_hand_front_door", 1001, 692060168, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_LEFT_HAND_FRONT_DOOR;Bodywork.BODYWORK_LEFT_HAND_FRONT_DOOR", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_right_hand_front_door", 1001, 692060170, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_RIGHT_HAND_FRONT_DOOR;Bodywork.BODYWORK_RIGHT_HAND_FRONT_DOOR", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_left_hand_rear_door", 1001, 692060172, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_LEFT_HAND_REAR_DOOR;Bodywork.BODYWORK_LEFT_HAND_REAR_DOOR", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_right_hand_rear_door", 1001, 692060174, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_RIGHT_HAND_REAR_DOOR;Bodywork.BODYWORK_RIGHT_HAND_REAR_DOOR", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_hood", 1001, 692060188, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_HOOD;Bodywork.BODYWORK_HOOD", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_luggage_door", 1001, 692060186, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_LUGGAGE_DOOR;Bodywork.BODYWORK_LUGGAGE_DOOR", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_1001_947912728_5", 1001, 947912728, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_WINDOW_LEFT_FRONT_PERCENT;Bodywork.BODYWORK_WINDOW_LEFT_FRONT_PERCENT", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_1001_1267728396_5", 1001, 1267728396, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_RIGHT_FRONT_WINDOW;Bodywork.BODYWORK_RIGHT_FRONT_WINDOW", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_1001_947912736_5", 1001, 947912736, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_WINDOW_LEFT_REAR_PERCENT;Bodywork.BODYWORK_WINDOW_LEFT_REAR_PERCENT", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_1001_947912752_5", 1001, 947912752, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_WINDOW_RIGHT_REAR_PERCENT;Bodywork.BODYWORK_WINDOW_RIGHT_REAR_PERCENT", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_sunroof_windoblind_position", 1001, 1101004852, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_SUNROOF_WINDOBLIND_POSITION;Bodywork.BODYWORK_SUNROOF_WINDOBLIND_POSITION", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_sunshade_panel_percent", 1001, 1101004816, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_SUNSHADE_PANEL_PERCENT;Bodywork.BODYWORK_SUNSHADE_PANEL_PERCENT", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("tyre_1016_-1728052956_5", 1016, -1728052956, TX_GET_INT, DirectValueDecoder.INT_KPA, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_VALUE_LEFT_FRONT;Tyre.TYRE_PRESSURE_VALUE_LEFT_FRONT", classification = "round_robin_changing_candidate", prodCategory = "tire_safety", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("tyre_1016_-1728052952_5", 1016, -1728052952, TX_GET_INT, DirectValueDecoder.INT_KPA, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_VALUE_RIGHT_FRONT;Tyre.TYRE_PRESSURE_VALUE_RIGHT_FRONT", classification = "round_robin_changing_candidate", prodCategory = "tire_safety", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("tyre_1016_-1728052948_5", 1016, -1728052948, TX_GET_INT, DirectValueDecoder.INT_KPA, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_VALUE_LEFT_REAR;Tyre.TYRE_PRESSURE_VALUE_LEFT_REAR", classification = "round_robin_changing_candidate", prodCategory = "tire_safety", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("tyre_1016_-1728052944_5", 1016, -1728052944, TX_GET_INT, DirectValueDecoder.INT_KPA, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_VALUE_RIGHT_REAR;Tyre.TYRE_PRESSURE_VALUE_RIGHT_REAR", classification = "round_robin_changing_candidate", prodCategory = "tire_safety", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("instrument_1007_1246797848_5", 1007, 1246797848, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_instrument", featureNames = "INSTRUMENT_2IN1_LF_TYRE_TEMPERATURE;Instrument.INSTRUMENT_2IN1_LF_TYRE_TEMPERATURE", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("instrument_1007_1246797860_5", 1007, 1246797860, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_instrument", featureNames = "INSTRUMENT_2IN1_RF_TYRE_TEMPERATURE;Instrument.INSTRUMENT_2IN1_RF_TYRE_TEMPERATURE", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("instrument_1007_1246797872_5", 1007, 1246797872, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_instrument", featureNames = "INSTRUMENT_2IN1_LB_TYRE_TEMPERATURE;Instrument.INSTRUMENT_2IN1_LB_TYRE_TEMPERATURE", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("instrument_1007_1246797884_5", 1007, 1246797884, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_instrument", featureNames = "INSTRUMENT_2IN1_RB_TYRE_TEMPERATURE;Instrument.INSTRUMENT_2IN1_RB_TYRE_TEMPERATURE", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("tyre_1016_-1728052957_5", 1016, -1728052957, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_STATE_LEFT_FRONT;Tyre.TYRE_PRESSURE_STATE_LEFT_FRONT", classification = "curated_debug_promotion", prodCategory = "curated_main", source = "live_reflection_device_map"),
        DirectFidEntry("tyre_1016_-1728052953_5", 1016, -1728052953, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_STATE_RIGHT_FRONT;Tyre.TYRE_PRESSURE_STATE_RIGHT_FRONT", classification = "curated_debug_promotion", prodCategory = "curated_main", source = "live_reflection_device_map"),
        DirectFidEntry("tyre_1016_-1728052949_5", 1016, -1728052949, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_STATE_LEFT_REAR;Tyre.TYRE_PRESSURE_STATE_LEFT_REAR", classification = "curated_debug_promotion", prodCategory = "curated_main", source = "live_reflection_device_map"),
        DirectFidEntry("tyre_1016_-1728052945_5", 1016, -1728052945, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_tyre", featureNames = "TYRE_PRESSURE_STATE_RIGHT_REAR;Tyre.TYRE_PRESSURE_STATE_RIGHT_REAR", classification = "curated_debug_promotion", prodCategory = "curated_main", source = "live_reflection_device_map"),
        DirectFidEntry("engine_front_motor_speed", 1012, 1141899272, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_engine", featureNames = "ENGINE_FRONT_MOTOR_SPEED;Engine.ENGINE_FRONT_MOTOR_SPEED", classification = "diagnostic_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ac_compressor_mode", 1000, 1077936137, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_ac", featureNames = "AC_COMPRESSOR_MODE;Ac.AC_COMPRESSOR_MODE", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("ac_1000_1077936176_5", 1000, 1077936176, TX_GET_INT, DirectValueDecoder.INT_TEMP_C, groupName = "direct_ac", featureNames = "AC_TEMP_DEPUTY;Ac.AC_TEMP_DEPUTY", classification = "round_robin_changing_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("bodywork_backdoor_position_feedback", 1001, 1074790456, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_bodywork", featureNames = "BODYWORK_BACKDOOR_POSITION_FEEDBACK;Bodywork.BODYWORK_BACKDOOR_POSITION_FEEDBACK", classification = "body_state_candidate", prodCategory = "body_cabin", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("charging_charging_vtov_discharge_status", 1009, 614465584, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_charging", featureNames = "Charging.CHARGING_VTOV_DISCHARGE_STATUS", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("engine_rear_motor_speed", 1012, 621805576, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_engine", featureNames = "ENGINE_REAR_MOTOR_SPEED;Engine.ENGINE_REAR_MOTOR_SPEED", classification = "diagnostic_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("engine_1012_1141899288_7", 1012, 1141899288, TX_GET_FLOAT, DirectValueDecoder.FLOAT_RAW, groupName = "direct_engine", featureNames = "ENGINE_FRONT_MOTOR_TORQUE;Engine.ENGINE_FRONT_MOTOR_TORQUE", classification = "round_robin_changing_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("engine_1012_1169162280_7", 1012, 1169162280, TX_GET_FLOAT, DirectValueDecoder.FLOAT_RAW, groupName = "direct_engine", featureNames = "ENGINE_REAR_MOTOR_TORQUE_F;Engine.ENGINE_REAR_MOTOR_TORQUE_F", classification = "round_robin_changing_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("gb_front_motor_ipm_temp", 1039, 1154482184, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_gb", featureNames = "GB_FRONT_MOTOR_IPM_TEMP;Gb.GB_FRONT_MOTOR_IPM_TEMP", classification = "diagnostic_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("gb_rear_motor_ipm_temp", 1039, 1155530760, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_gb", featureNames = "GB_REAR_MOTOR_IPM_TEMP;Gb.GB_REAR_MOTOR_IPM_TEMP", classification = "diagnostic_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("gb_1039_1169162248_5", 1039, 1169162248, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_gb", featureNames = "GB_FRONT_MOTOR_BUS_VOLTAGE;Gb.GB_FRONT_MOTOR_BUS_VOLTAGE", classification = "round_robin_changing_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("gb_rear_motor_bus_voltage", 1039, 1169162264, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_gb", featureNames = "GB_REAR_MOTOR_BUS_VOLTAGE;Gb.GB_REAR_MOTOR_BUS_VOLTAGE", classification = "diagnostic_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("gearbox_1011_555745336_5", 1011, 555745336, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_gearbox", featureNames = "GEARBOX_AUTO_MODE_TYPE;Gearbox.GEARBOX_AUTO_MODE_TYPE", classification = "round_robin_changing_candidate", prodCategory = "motion_powertrain", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("pm2p5_value_in", 1008, 1331691536, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_pm2p5", featureNames = "PM2P5_VALUE_IN;Pm2p5.PM2P5_VALUE_IN", classification = "environment_candidate", prodCategory = "unknown_keep_queryable", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("pm2p5_value_out", 1008, 1331691548, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_pm2p5", featureNames = "PM2P5_VALUE_OUT;Pm2p5.PM2P5_VALUE_OUT", classification = "environment_candidate", prodCategory = "unknown_keep_queryable", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053151_5", 1025, -1728053151, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_LEFT_FRONT;Radar.RADAR_OBSTACLE_DISTANCE_LEFT_FRONT", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053150_5", 1025, -1728053150, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_FRONT_LEFT_MID;Radar.RADAR_OBSTACLE_DISTANCE_FRONT_LEFT_MID", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053149_5", 1025, -1728053149, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_FRONT_RIGHT_MID;Radar.RADAR_OBSTACLE_DISTANCE_FRONT_RIGHT_MID", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053148_5", 1025, -1728053148, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_RIGHT_FRONT;Radar.RADAR_OBSTACLE_DISTANCE_RIGHT_FRONT", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053147_5", 1025, -1728053147, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_LEFT;Radar.RADAR_OBSTACLE_DISTANCE_LEFT", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053146_5", 1025, -1728053146, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_LEFT_REAR;Radar.RADAR_OBSTACLE_DISTANCE_LEFT_REAR", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053145_5", 1025, -1728053145, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_RIGHT_REAR;Radar.RADAR_OBSTACLE_DISTANCE_RIGHT_REAR", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("radar_1025_-1728053144_5", 1025, -1728053144, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_radar", featureNames = "RADAR_OBSTACLE_DISTANCE_RIGHT;Radar.RADAR_OBSTACLE_DISTANCE_RIGHT", classification = "round_robin_changing_candidate", prodCategory = "adas_research", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_max_charge_power_allow", 1014, 877658136, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_statistic", featureNames = "STATISTIC_MAX_CHARGE_POWER_ALLOW;Statistic.STATISTIC_MAX_CHARGE_POWER_ALLOW", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_average_battery_temp", 1014, 1148190776, TX_GET_INT, DirectValueDecoder.INT_TEMP_C_OFS40, groupName = "direct_statistic", featureNames = "STATISTIC_AVERAGE_BATTERY_TEMP;Statistic.STATISTIC_AVERAGE_BATTERY_TEMP", classification = "vehicle_energy_candidate", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("charging_1009_89128973_5", 1009, 89128973, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_charging", featureNames = "CHARGING_CHARGER_CONNECT_STATE;Charging.CHARGING_CHARGER_CONNECT_STATE", classification = "round_robin_promoted_20260613", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
        DirectFidEntry("statistic_1014_877658120_5", 1014, 877658120, TX_GET_INT, DirectValueDecoder.INT_RAW, groupName = "direct_statistic", featureNames = "STATISTIC_MAX_DISCHARGE_POWER_ALLOW;Statistic.STATISTIC_MAX_DISCHARGE_POWER_ALLOW", classification = "round_robin_promoted_20260613", prodCategory = "charging_energy", source = "wide-poll-session-20260605_161751"),
    )
}

object DirectValueDecoders {
    fun rawString(raw: Int): String = raw.toString()

    fun decode(entry: DirectFidEntry, raw: Int): String? {
        return when (entry.decoder) {
            DirectValueDecoder.INT_RAW,
            DirectValueDecoder.INT_ENUM,
            DirectValueDecoder.INT_KPA -> decodeInt(raw)?.toString()
            DirectValueDecoder.INT_PERCENT -> decodeInt(raw)?.takeIf { it in 0..100 }?.toString()
            DirectValueDecoder.INT_TEMP_C -> decodeInt(raw)?.takeIf { it in -50..80 }?.toString()
            DirectValueDecoder.INT_TEMP_C_OFS40 -> decodeInt(raw)?.minus(40)?.takeIf { it in -50..80 }?.toString()
            DirectValueDecoder.INT_SCALED -> decodeInt(raw)?.let { formatDecimal(it * entry.scale) }
            DirectValueDecoder.FLOAT_RAW -> decodeFloat(Float.fromBits(raw))?.let { formatDecimal(it.toDouble()) }
            DirectValueDecoder.FLOAT_PERCENT -> decodeFloat(Float.fromBits(raw))
                ?.takeIf { it in 0.0f..100.0f }
                ?.let { formatDecimal(it.toDouble()) }
            DirectValueDecoder.FLOAT_KWH -> decodeFloat(Float.fromBits(raw))
                ?.takeIf { it >= 0.0f }
                ?.let { formatDecimal(it.toDouble()) }
            DirectValueDecoder.FLOAT_VOLT -> decodeFloat(Float.fromBits(raw))
                ?.takeIf { it in 0.0f..1000.0f }
                ?.let { formatDecimal(it.toDouble()) }
        }
    }

    private fun decodeInt(raw: Int): Int? {
        return raw.takeUnless {
            it == WRONG_TRANSACT ||
                it == WRONG_DIRECTION ||
                it == FEATURE_LINK_ERROR ||
                it == NOT_INITIALIZED_20BIT
        }
    }

    private fun decodeFloat(raw: Float): Float? {
        return raw.takeUnless { it.isNaN() || it.isInfinite() || it == -1.0f }
    }

    private fun formatDecimal(value: Double): String {
        val text = String.format(Locale.US, "%.6f", value)
        return text.trimEnd('0').trimEnd('.')
    }

    private const val FEATURE_LINK_ERROR = 65535
    private const val NOT_INITIALIZED_20BIT = 1048575
    private const val WRONG_TRANSACT = -10013
    private const val WRONG_DIRECTION = -10011
}
