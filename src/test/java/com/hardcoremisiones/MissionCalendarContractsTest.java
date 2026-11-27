package com.hardcoremisiones;

import com.hardcoremisiones.missions.cycle.MissionCalendar;

/** Clock arithmetic boundaries used by the persisted Paper clock adapter. */
public final class MissionCalendarContractsTest {
    private static void eq(long expected,long actual){if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
    public static void main(String[] args){
        eq(18000,MissionCalendar.nextMidnight(0,18000));
        eq(42000,MissionCalendar.nextMidnight(0,18001));
        eq(18000,MissionCalendar.nextMidnight(17999,1));
        eq(42000,MissionCalendar.nextMidnight(18000,1));
        eq(42000,MissionCalendar.nextMidnight(18000,24000));
        eq(24000,MissionCalendar.nextAssignment(23999));
        eq(24000,MissionCalendar.nextAssignment(24000));
        eq(25999,MissionCalendar.nextAssignment(25999));
        eq(48000,MissionCalendar.nextAssignment(26000));
        eq(0,MissionCalendar.recoverGroupDays(0,true,false));
        eq(0,MissionCalendar.recoverGroupDays(0,false,true));
        eq(2,MissionCalendar.recoverGroupDays(2,false,false));
        eq(200,MissionCalendar.resumeExact(200,0));
        eq(350,MissionCalendar.resumeExact(200,150));
        try{MissionCalendar.recoverGroupDays(0,false,false);throw new AssertionError("zero active");}catch(IllegalArgumentException expected){}
        try{MissionCalendar.resumeExact(10,-1);throw new AssertionError("negative remaining");}catch(IllegalArgumentException expected){}
        System.out.println("MISSION_CALENDAR_CONTRACTS_PASS 16 checks");
    }
}
