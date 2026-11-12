import com.fasterxml.jackson.dataformat.xml.*;
import com.fasterxml.jackson.dataformat.xml.annotation.*;
import java.util.*;
public class Jxml {
  @JacksonXmlRootElement(localName = "order")
  public static class Order {
    @JacksonXmlProperty(isAttribute = true) public String id;
    public String customer;
    @JacksonXmlElementWrapper(localName = "items") @JacksonXmlProperty(localName = "item") public List<Item> items = new ArrayList<>();
  }
  public static class Item { @JacksonXmlProperty(isAttribute = true) public String sku; public int qty; public double price; @JacksonXmlText public String note; }
  public static void main(String[] a) throws Exception {
    XmlMapper m = new XmlMapper();
    Order o = new Order(); o.id = "o-1"; o.customer = "Ada & Co <ltd>";
    Item i1 = new Item(); i1.sku = "A1"; i1.qty = 2; i1.price = 9.5; o.items.add(i1);
    String xml = m.writeValueAsString(o);
    System.out.println(xml);
    Order back = m.readValue(xml, Order.class);
    System.out.println(back.id + " " + back.customer + " " + back.items.size() + " " + back.items.get(0).sku + " " + back.items.get(0).price);
    System.out.println(m.readTree("<r a=\"1\"><x>2</x><x>3</x><y/></r>"));
    System.out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("k", List.of(1, 2))));
    System.out.println(javax.xml.stream.XMLInputFactory.newFactory().getClass().getName());
  }
}
