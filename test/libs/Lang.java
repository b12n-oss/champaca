import org.apache.commons.lang3.StringUtils;
public class Lang {
  public static void main(String[] a) {
    System.out.println(StringUtils.capitalize("champaca"));
    System.out.println(StringUtils.reverse("jolt"));
    System.out.println(StringUtils.join(new String[]{"a","b","c"}, "-"));
    System.out.println(StringUtils.isBlank("  "));
    System.out.println(StringUtils.abbreviate("a long sentence here", 10));
    System.out.println(StringUtils.leftPad("7", 3, '0'));
  }
}
