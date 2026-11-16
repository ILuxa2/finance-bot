package financeos.core;
public final class QuickOperationParserTest {
 public static void main(String[]a){
  var p=new QuickOperationParser();var e=p.parse("−450.50, кофе, булочка");if(e.kind()!=EntryKind.EXPENSE || !e.comment().equals("кофе, булочка") || e.amount().compareTo(Money.of("450.50"))!=0)throw new AssertionError();
  if(p.parse(" +50000, зарплата ").kind()!=EntryKind.INCOME || p.parse("-1, тест").kind()!=EntryKind.EXPENSE)throw new AssertionError();
  for(String bad:new String[]{"450, кофе","-0, кофе","+-1, кофе","--1, кофе","−1.001, кофе","+1e6, кофе","−1, ","-1.00.00, x","-1000000000000000, x","+1, "+"x".repeat(501)}){
   try{p.parse(bad);throw new AssertionError("Accepted malformed input "+bad);}catch(IllegalArgumentException expected){}
  }
  System.out.println("Quick parser: explicit ASCII/Unicode signs, decimals, comma comments, positive/length/precision limits passed.");
 }
}
