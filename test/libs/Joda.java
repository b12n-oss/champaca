import org.joda.time.*;
import org.joda.time.format.*;
public class Joda {
  public static void main(String[] a) {
    DateTimeZone ny = DateTimeZone.forID("America/New_York");
    DateTime t = new DateTime(2026, 3, 8, 1, 30, 0, 0, ny);
    System.out.println(t + " " + t.plusHours(1) + " " + t.plusHours(2));
    System.out.println(ny.getOffset(t.getMillis()) + " " + ny.isStandardOffset(t.getMillis()) + " " + ny.nextTransition(t.getMillis()));
    DateTimeFormatter f = DateTimeFormat.forPattern("EEEE, d MMMM yyyy HH:mm z").withLocale(java.util.Locale.US);
    System.out.println(f.print(t.withZone(DateTimeZone.forID("Europe/Paris"))));
    System.out.println(ISODateTimeFormat.dateTime().parseDateTime("2026-11-12T23:55:00.123+11:00").toDateTime(DateTimeZone.UTC));
    Period p = new Period(new LocalDate(2024, 2, 29), new LocalDate(2026, 11, 12));
    System.out.println(p + " " + PeriodFormat.getDefault().print(p) + " " + Days.daysBetween(new LocalDate(2024, 2, 29), new LocalDate(2026, 11, 12)).getDays());
    System.out.println(new Interval(t, t.plusDays(3)).toDuration() + " " + new LocalTime(23, 59).plusMinutes(2) + " " + DateTimeZone.getAvailableIDs().size());
  }
}
