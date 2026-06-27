package com.bydcollector.collector.data.normalized

//semantic catalog that maps curated raw keys to stable fields exposed to dashboard, mqtt, and influx
object NormalizedFieldCatalog {
    const val CATALOG_VERSION = "normalized-direct-v8-20260627-charge-current"

    val soc = number(
        fieldKey = "soc",
        category = NormalizedCategory.BATTERY,
        unit = "%",
        displayName = "Battery SOC display",
        deviceClass = "battery",
        stateClass = "measurement",
        sourceKeys = listOf("statistic_1014_1145045040_5"),
        normalizerId = "decoded_percent_0_100"
    )
    val socEstimate = number(
        fieldKey = "soc_estimate",
        category = NormalizedCategory.BATTERY,
        unit = "%",
        displayName = "Battery SOC estimate",
        deviceClass = "battery",
        stateClass = "measurement",
        sourceKeys = listOf("statistic_1014_1134559272_5"),
        normalizerId = "decoded_percent_0_100"
    )

    val batterySoh = number("battery_soh_percent", NormalizedCategory.BATTERY, "%", "Battery SOH", "battery", "measurement", listOf("statistic_1014_1145045032_5"), "decoded_percent_0_100")
    val hvBatteryVoltage = number("hv_battery_voltage_v", NormalizedCategory.BATTERY, "V", "HV battery voltage", "voltage", "measurement", listOf("charging_charge_battery_volt"), "decoded_voltage_v")
    val chargeCurrent = number("charge_current_a", NormalizedCategory.BATTERY, "A", "Charge current", "current", "measurement", listOf("charging_charge_current"), "decoded_current_a")
    //splits hv power into signed/charge/discharge views because charging power alone misses driving/v2l discharge
    val batteryPower = number("battery_power_kw", NormalizedCategory.BATTERY, "kW", "Battery power", "power", "measurement", listOf("charging_charge_battery_volt", "charging_charge_current"), "derived_signed_hv_power_kw")
    val batteryChargePower = number("battery_charge_power_kw", NormalizedCategory.BATTERY, "kW", "Battery charge power", "power", "measurement", listOf("charging_charge_battery_volt", "charging_charge_current"), "derived_hv_charge_power_kw")
    val batteryDischargePower = number("battery_discharge_power_kw", NormalizedCategory.BATTERY, "kW", "Battery discharge power", "power", "measurement", listOf("charging_charge_battery_volt", "charging_charge_current"), "derived_hv_discharge_power_kw")
    val auxVoltage = number("aux_voltage_v", NormalizedCategory.BATTERY, "V", "12V battery", "voltage", "measurement", listOf("ota_battery_voltage"), "decoded_voltage_v")
    val batteryHighestCellVoltage = number("battery_highest_cell_voltage_raw", NormalizedCategory.BATTERY, "V", "Cell max voltage", "voltage", "measurement", listOf("statistic_highest_battery_voltage"), "raw_number_milli_non_negative")
    val batteryLowestCellVoltage = number("battery_lowest_cell_voltage_raw", NormalizedCategory.BATTERY, "V", "Cell min voltage", "voltage", "measurement", listOf("statistic_lowest_battery_voltage"), "raw_number_milli_non_negative")
    val insideTemp = number("inside_temp_c_raw", NormalizedCategory.CLIMATE, "°C", "Cabin temperature", "temperature", "measurement", listOf("ac_ac_temp_inside"), "raw_temperature_c")
    val outsideTemp = number("outside_temp_c_raw", NormalizedCategory.CLIMATE, "°C", "Outside temperature", "temperature", "measurement", listOf("ac_1000_1077936184_5"), "raw_temperature_c")
    val batteryHighestTemp = number("battery_highest_temp_raw", NormalizedCategory.BATTERY, "°C", "Battery max temperature", "temperature", "measurement", listOf("statistic_1014_1148190752_5"), "raw_temp_c_offset_40")
    val batteryLowestTemp = number("battery_lowest_temp_raw", NormalizedCategory.BATTERY, "°C", "Battery min temperature", "temperature", "measurement", listOf("statistic_1014_1148190736_5"), "raw_temp_c_offset_40")
    val frontMotorTemp = number("front_motor_temp_raw", NormalizedCategory.MOTION, "°C", "Front motor temperature", "temperature", "measurement", listOf("gb_1039_1154482192_5"), "decoded_number_raw")
    val rearMotorTemp = number("rear_motor_temp_raw", NormalizedCategory.MOTION, "°C", "Rear motor temperature", "temperature", "measurement", listOf("gb_trear_motor_temp"), "decoded_number_raw")
    val remainingRangeKm = number("remaining_range_km", NormalizedCategory.BATTERY, "km", "EV range", "distance", "measurement", listOf("statistic_elec_driving_range_yun"), "decoded_number_non_negative")
    val odometerKm = number("odometer_km", NormalizedCategory.MOTION, "km", "Odometer", "distance", "total_increasing", listOf("statistic_1014_1246765072_5"), "raw_number_deci_non_negative")
    val speedKmh = number("speed_kmh", NormalizedCategory.MOTION, "km/h", "Speed", "speed", "measurement", listOf("speed_1013_-1807745016_7"), "decoded_speed_kmh")
    val chargingState = rawEnum("charging_state", NormalizedCategory.BATTERY, "Charging state", "charging_1009_1231032336_5")
    val chargeGunConnected = bool("charge_gun_connected_raw", NormalizedCategory.BATTERY, "Charging gun", "plug", listOf("charging_1009_876609586_5"), "zero_false_nonzero_true")
    val acPower = bool("ac_power", NormalizedCategory.CLIMATE, "Climate", null, listOf("ac_1000_1077936144_5"), "zero_false_nonzero_true")
    val driverTempSetpoint = number("driver_temp_setpoint_raw", NormalizedCategory.CLIMATE, "°C", "Driver temperature setting", "temperature", "measurement", listOf("ac_1000_1077936168_5"), "raw_temperature_c", mqttDefaultEnabled = false)
    val acWindLevel = number("ac_wind_level_raw", NormalizedCategory.CLIMATE, "level", "Fan speed", null, "measurement", listOf("ac_wind_level"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val driverDoorLock = bool("ota_lf_door_lock", NormalizedCategory.BODY, "Driver door lock", null, listOf("ota_lf_door_lock"), "door_lock_state_locked", mqttDefaultEnabled = false)
    val passengerDoorLock = bool("rf_door_lock_raw", NormalizedCategory.BODY, "Passenger door lock", null, listOf("bodywork_rf_door_lock_status"), "door_lock_state_locked", mqttDefaultEnabled = false)
    val rearLeftDoorLock = bool("lr_door_lock_raw", NormalizedCategory.BODY, "Rear left door lock", null, listOf("bodywork_lr_door_lock_status"), "door_lock_state_locked", mqttDefaultEnabled = false)
    val rearRightDoorLock = bool("rr_door_lock_raw", NormalizedCategory.BODY, "Rear right door lock", null, listOf("bodywork_rr_door_lock_status"), "door_lock_state_locked", mqttDefaultEnabled = false)
    val driverDoor = bool("driver_door_open", NormalizedCategory.BODY, "Driver door", "door", listOf("bodywork_left_hand_front_door"), "zero_closed_nonzero_open")
    val passengerDoor = bool("passenger_door_open", NormalizedCategory.BODY, "Passenger door", "door", listOf("bodywork_right_hand_front_door"), "zero_closed_nonzero_open")
    val leftRearDoor = bool("left_rear_door_open", NormalizedCategory.BODY, "Rear left door", "door", listOf("bodywork_left_hand_rear_door"), "zero_closed_nonzero_open")
    val rightRearDoor = bool("right_rear_door_open", NormalizedCategory.BODY, "Rear right door", "door", listOf("bodywork_right_hand_rear_door"), "zero_closed_nonzero_open")
    val hood = bool("hood_open", NormalizedCategory.BODY, "Hood", "opening", listOf("bodywork_hood"), "zero_closed_nonzero_open")
    val trunkDoor = bool("trunk_open", NormalizedCategory.BODY, "Trunk", "door", listOf("bodywork_luggage_door"), "zero_closed_nonzero_open")
    val leftFrontWindow = number("lf_window_percent", NormalizedCategory.BODY, "%", "Driver window", null, "measurement", listOf("bodywork_1001_947912728_5"), "decoded_percent_0_100")
    val rightFrontWindow = bool("rf_window_open_raw", NormalizedCategory.BODY, "Passenger window", "window", listOf("bodywork_1001_1267728396_5"), "zero_false_nonzero_true")
    val leftRearWindow = number("lr_window_percent", NormalizedCategory.BODY, "%", "Rear left window", null, "measurement", listOf("bodywork_1001_947912736_5"), "decoded_percent_0_100")
    val rightRearWindow = number("rr_window_percent", NormalizedCategory.BODY, "%", "Rear right window", null, "measurement", listOf("bodywork_1001_947912752_5"), "decoded_percent_0_100")
    val sunroofPosition = bool("bodywork_sunroof_windoblind_position", NormalizedCategory.BODY, "Sunroof position", "opening", listOf("bodywork_sunroof_windoblind_position"), "zero_false_nonzero_true")
    val panoramaSunshade = number("bodywork_sunshade_panel_percent", NormalizedCategory.BODY, "%", "Panorama sunshade status", null, "measurement", listOf("bodywork_sunshade_panel_percent"), "decoded_percent_0_100")
    val tirePressureLf = number("tire_pressure_lf_raw", NormalizedCategory.SAFETY, "kPa", "Driver tire pressure", "pressure", "measurement", listOf("tyre_1016_-1728052956_5"), "raw_number_kpa_non_negative")
    val tirePressureRf = number("tire_pressure_rf_raw", NormalizedCategory.SAFETY, "kPa", "Passenger tire pressure", "pressure", "measurement", listOf("tyre_1016_-1728052952_5"), "raw_number_kpa_non_negative")
    val tirePressureLr = number("tire_pressure_lr_raw", NormalizedCategory.SAFETY, "kPa", "Rear left tire pressure", "pressure", "measurement", listOf("tyre_1016_-1728052948_5"), "raw_number_kpa_non_negative")
    val tirePressureRr = number("tire_pressure_rr_raw", NormalizedCategory.SAFETY, "kPa", "Rear right tire pressure", "pressure", "measurement", listOf("tyre_1016_-1728052944_5"), "raw_number_kpa_non_negative")
    val tireTempLf = number("tire_temp_lf_raw", NormalizedCategory.SAFETY, "°C", "Driver tire temperature", "temperature", "measurement", listOf("instrument_1007_1246797848_5"), "decoded_number_raw")
    val tireTempRf = number("tire_temp_rf_raw", NormalizedCategory.SAFETY, "°C", "Passenger tire temperature", "temperature", "measurement", listOf("instrument_1007_1246797860_5"), "decoded_number_raw")
    val tireTempLr = number("tire_temp_lr_raw", NormalizedCategory.SAFETY, "°C", "Rear left tire temperature", "temperature", "measurement", listOf("instrument_1007_1246797872_5"), "decoded_number_raw")
    val tireTempRr = number("tire_temp_rr_raw", NormalizedCategory.SAFETY, "°C", "Rear right tire temperature", "temperature", "measurement", listOf("instrument_1007_1246797884_5"), "decoded_number_raw")
    val tireStateLf = rawEnum("tyre_state_lf", NormalizedCategory.SAFETY, "Driver tire status", "tyre_1016_-1728052957_5")
    val tireStateRf = rawEnum("tyre_state_rf", NormalizedCategory.SAFETY, "Passenger tire status", "tyre_1016_-1728052953_5")
    val tireStateLr = rawEnum("tyre_state_lr", NormalizedCategory.SAFETY, "Rear left tire status", "tyre_1016_-1728052949_5")
    val tireStateRr = rawEnum("tyre_state_rr", NormalizedCategory.SAFETY, "Rear right tire status", "tyre_1016_-1728052945_5")
    val frontMotorSpeed = number("front_motor_speed_raw", NormalizedCategory.MOTION, "RPM", "Front motor speed", null, "measurement", listOf("engine_front_motor_speed"), "decoded_number_raw")
    val acCompressorMode = bool("ac_compressor_mode_raw", NormalizedCategory.CLIMATE, "AC compressor mode raw", null, listOf("ac_compressor_mode"), "zero_false_nonzero_true", mqttDefaultEnabled = false)
    val passengerTempSetpoint = number("passenger_temp_setpoint_raw", NormalizedCategory.CLIMATE, "°C", "Passenger temperature setpoint", "temperature", "measurement", listOf("ac_1000_1077936176_5"), "raw_temperature_c", mqttDefaultEnabled = false)
    val trunkPositionPercent = number("trunk_position_percent_raw", NormalizedCategory.BODY, "%", "Trunk position percent raw", null, "measurement", listOf("bodywork_backdoor_position_feedback"), "decoded_percent_0_100")
    val v2lDischargeActive = bool("v2l_discharge_active_raw", NormalizedCategory.BATTERY, "V2L discharge active raw", null, listOf("charging_charging_vtov_discharge_status"), "zero_false_nonzero_true")
    val rearMotorSpeed = number("rear_motor_speed_raw", NormalizedCategory.MOTION, "RPM", "Rear motor speed raw", null, "measurement", listOf("engine_rear_motor_speed"), "decoded_number_raw")
    val frontMotorTorque = number("front_motor_torque", NormalizedCategory.MOTION, "Nm", "Front motor torque", null, "measurement", listOf("engine_1012_1141899288_7"), "decoded_number_raw")
    val rearMotorTorque = number("rear_motor_torque", NormalizedCategory.MOTION, "Nm", "Rear motor torque", null, "measurement", listOf("engine_1012_1169162280_7"), "decoded_number_raw")
    val frontMotorIpmTemp = number("front_motor_ipm_temp_raw", NormalizedCategory.MOTION, "°C", "Front motor IPM temperature raw", "temperature", "measurement", listOf("gb_front_motor_ipm_temp"), "decoded_number_raw")
    val rearMotorIpmTemp = number("rear_motor_ipm_temp_raw", NormalizedCategory.MOTION, "°C", "Rear motor IPM temperature raw", "temperature", "measurement", listOf("gb_rear_motor_ipm_temp"), "decoded_number_raw")
    val frontMotorBusVoltage = number("front_motor_bus_voltage_raw", NormalizedCategory.MOTION, "V", "Front motor bus voltage raw", "voltage", "measurement", listOf("gb_1039_1169162248_5"), "decoded_voltage_v")
    val rearMotorBusVoltage = number("rear_motor_bus_voltage_raw", NormalizedCategory.MOTION, "V", "Rear motor bus voltage raw", "voltage", "measurement", listOf("gb_rear_motor_bus_voltage"), "decoded_voltage_v")
    val gearAutoMode = rawEnum("gear_auto_mode_raw", NormalizedCategory.MOTION, "Gear auto mode raw", "gearbox_1011_555745336_5")
    val pm25Inside = number("pm25_inside", NormalizedCategory.CLIMATE, "µg/m³", "PM2.5 inside", null, "measurement", listOf("pm2p5_value_in"), "decoded_number_non_negative")
    val pm25Outside = number("pm25_outside", NormalizedCategory.CLIMATE, "µg/m³", "PM2.5 outside", null, "measurement", listOf("pm2p5_value_out"), "decoded_number_non_negative")
    val radarLeftFront = number("radar_1025_neg_1728053151_5", NormalizedCategory.SAFETY, "cm", "Radar left front", "distance", "measurement", listOf("radar_1025_-1728053151_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val radarFrontLeftMid = number("radar_1025_neg_1728053150_5", NormalizedCategory.SAFETY, "cm", "Radar front left mid", "distance", "measurement", listOf("radar_1025_-1728053150_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val radarFrontRightMid = number("radar_1025_neg_1728053149_5", NormalizedCategory.SAFETY, "cm", "Radar front right mid", "distance", "measurement", listOf("radar_1025_-1728053149_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val radarRightFront = number("radar_1025_neg_1728053148_5", NormalizedCategory.SAFETY, "cm", "Radar right front", "distance", "measurement", listOf("radar_1025_-1728053148_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val radarLeft = number("radar_1025_neg_1728053147_5", NormalizedCategory.SAFETY, "cm", "Radar left", "distance", "measurement", listOf("radar_1025_-1728053147_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val radarLeftRear = number("radar_1025_neg_1728053146_5", NormalizedCategory.SAFETY, "cm", "Radar left rear", "distance", "measurement", listOf("radar_1025_-1728053146_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val radarRightRear = number("radar_1025_neg_1728053145_5", NormalizedCategory.SAFETY, "cm", "Radar right rear", "distance", "measurement", listOf("radar_1025_-1728053145_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val radarRight = number("radar_1025_neg_1728053144_5", NormalizedCategory.SAFETY, "cm", "Radar right", "distance", "measurement", listOf("radar_1025_-1728053144_5"), "decoded_number_non_negative", mqttDefaultEnabled = false)
    val maxChargePowerAllow = number("max_charge_power_allow_raw", NormalizedCategory.BATTERY, "kW", "Max charge power allow raw", "power", "measurement", listOf("statistic_max_charge_power_allow"), "decoded_number_non_negative")
    val batteryAverageTemp = number("battery_average_temp_raw", NormalizedCategory.BATTERY, "°C", "Battery average temperature", "temperature", "measurement", listOf("statistic_average_battery_temp"), "raw_temp_c_offset_40")
    val chargerConnected = bool("charger_connected_raw", NormalizedCategory.BATTERY, "Charger connected raw", "plug", listOf("charging_1009_89128973_5"), "zero_false_nonzero_true")
    val maxDischargePowerAllow = number("max_discharge_power_allow_raw", NormalizedCategory.BATTERY, "kW", "Max discharge power allow raw", "power", "measurement", listOf("statistic_1014_877658120_5"), "decoded_number_non_negative")

    val fields: List<NormalizedFieldDefinition> = listOf(
        soc,
        socEstimate,
        batterySoh,
        hvBatteryVoltage,
        chargeCurrent,
        batteryPower,
        batteryChargePower,
        batteryDischargePower,
        auxVoltage,
        batteryHighestCellVoltage,
        batteryLowestCellVoltage,
        insideTemp,
        outsideTemp,
        batteryHighestTemp,
        batteryLowestTemp,
        frontMotorTemp,
        rearMotorTemp,
        remainingRangeKm,
        odometerKm,
        speedKmh,
        chargingState,
        chargeGunConnected,
        acPower,
        driverTempSetpoint,
        acWindLevel,
        driverDoorLock,
        passengerDoorLock,
        rearLeftDoorLock,
        rearRightDoorLock,
        driverDoor,
        passengerDoor,
        leftRearDoor,
        rightRearDoor,
        hood,
        trunkDoor,
        leftFrontWindow,
        rightFrontWindow,
        leftRearWindow,
        rightRearWindow,
        sunroofPosition,
        panoramaSunshade,
        tirePressureLf,
        tirePressureRf,
        tirePressureLr,
        tirePressureRr,
        tireTempLf,
        tireTempRf,
        tireTempLr,
        tireTempRr,
        tireStateLf,
        tireStateRf,
        tireStateLr,
        tireStateRr,
        frontMotorSpeed,
        acCompressorMode,
        passengerTempSetpoint,
        trunkPositionPercent,
        v2lDischargeActive,
        rearMotorSpeed,
        frontMotorTorque,
        rearMotorTorque,
        frontMotorIpmTemp,
        rearMotorIpmTemp,
        frontMotorBusVoltage,
        rearMotorBusVoltage,
        gearAutoMode,
        pm25Inside,
        pm25Outside,
        radarLeftFront,
        radarFrontLeftMid,
        radarFrontRightMid,
        radarRightFront,
        radarLeft,
        radarLeftRear,
        radarRightRear,
        radarRight,
        maxChargePowerAllow,
        batteryAverageTemp,
        chargerConnected,
        maxDischargePowerAllow
    )

    private fun rawEnum(
        fieldKey: String,
        category: NormalizedCategory,
        displayName: String,
        sourceKey: String
    ): NormalizedFieldDefinition {
        return number(
            fieldKey = fieldKey,
            category = category,
            unit = null,
            displayName = displayName,
            deviceClass = null,
            stateClass = null,
            sourceKeys = listOf(sourceKey),
            normalizerId = "decoded_number_raw",
            mqttDefaultEnabled = false
        )
    }

    private fun number(
        fieldKey: String,
        category: NormalizedCategory,
        unit: String?,
        displayName: String,
        deviceClass: String?,
        stateClass: String?,
        sourceKeys: List<String>,
        normalizerId: String,
        mqttDefaultEnabled: Boolean = true
    ): NormalizedFieldDefinition {
        return NormalizedFieldDefinition(
            fieldKey = fieldKey,
            category = category,
            valueType = NormalizedValueType.NUMBER,
            unit = unit,
            displayName = displayName,
            deviceClass = deviceClass,
            stateClass = stateClass,
            entityPlatform = "sensor",
            sourceKeys = sourceKeys,
            normalizerId = normalizerId,
            mqttDefaultEnabled = mqttDefaultEnabled
        )
    }

    private fun bool(
        fieldKey: String,
        category: NormalizedCategory,
        displayName: String,
        deviceClass: String?,
        sourceKeys: List<String>,
        normalizerId: String,
        mqttDefaultEnabled: Boolean = true
    ): NormalizedFieldDefinition {
        return NormalizedFieldDefinition(
            fieldKey = fieldKey,
            category = category,
            valueType = NormalizedValueType.BOOLEAN,
            unit = null,
            displayName = displayName,
            deviceClass = deviceClass,
            stateClass = null,
            entityPlatform = "binary_sensor",
            sourceKeys = sourceKeys,
            normalizerId = normalizerId,
            mqttDefaultEnabled = mqttDefaultEnabled
        )
    }
}
