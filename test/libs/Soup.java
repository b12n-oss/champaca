import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
public class Soup {
  public static void main(String[] a) {
    Document d = Jsoup.parse("<html><head><title>Champaca</title></head><body><div id=main class='x y'><p>One <b>two</b></p><a href='/jolt'>jolt</a><a href='https://example.com/x'>ex</a><ul><li>a<li>b<li>c</ul></div></body></html>", "https://b12n.net/");
    System.out.println(d.title());
    System.out.println(d.select("p").text());
    for (Element e : d.select("a[href]")) System.out.println(e.text() + " -> " + e.absUrl("href"));
    System.out.println(d.select("li").size() + " " + d.select("li").eachText());
    System.out.println(d.getElementById("main").classNames());
    System.out.println(Jsoup.clean("<p onclick='x()'>hi<script>bad()</script></p>", org.jsoup.safety.Safelist.basic()));
    System.out.println(d.body().child(0).child(0).outerHtml());
  }
}
