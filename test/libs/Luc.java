import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import java.nio.file.*;
public class Luc {
  public static void main(String[] a) throws Exception {
    Path dir = Files.createTempDirectory("lucene");
    try (Directory d = FSDirectory.open(dir)) {
      System.out.println(d.getClass().getSimpleName());
      StandardAnalyzer an = new StandardAnalyzer();
      try (IndexWriter w = new IndexWriter(d, new IndexWriterConfig(an))) {
        String[][] docs = {{"1", "The quick brown fox jumps over the lazy dog", "10"}, {"2", "Champaca is a Java runtime inside jolt", "20"},
                           {"3", "Lucene indexes text with an inverted index", "30"}, {"4", "A brown dog and a quick cat", "40"}};
        for (String[] x : docs) { Document doc = new Document(); doc.add(new StringField("id", x[0], Field.Store.YES)); doc.add(new TextField("body", x[1], Field.Store.YES));
          doc.add(new IntPoint("n", Integer.parseInt(x[2]))); doc.add(new NumericDocValuesField("n", Integer.parseInt(x[2]))); w.addDocument(doc); }
        w.commit();
      }
      try (DirectoryReader r = DirectoryReader.open(d)) {
        IndexSearcher s = new IndexSearcher(r);
        for (String q : new String[] {"quick dog", "\"brown fox\"", "java OR lucene", "jum*", "body:index~1"}) {
          TopDocs td = s.search(new QueryParser("body", an).parse(q), 10);
          StringBuilder sb = new StringBuilder(q + " -> " + td.totalHits.value() + ":");
          for (ScoreDoc sd : td.scoreDocs) sb.append(" ").append(s.storedFields().document(sd.doc).get("id"));
          System.out.println(sb);
        }
        System.out.println("range " + s.count(IntPoint.newRangeQuery("n", 15, 35)) + " docs " + r.numDocs() + " segments " + r.leaves().size());
        TopDocs sorted = s.search(new MatchAllDocsQuery(), 4, new Sort(new SortField("n", SortField.Type.INT, true)));
        StringBuilder sb = new StringBuilder("sorted:"); for (ScoreDoc sd : sorted.scoreDocs) sb.append(" ").append(s.storedFields().document(sd.doc).get("id")); System.out.println(sb);
      }
    }
  }
}
