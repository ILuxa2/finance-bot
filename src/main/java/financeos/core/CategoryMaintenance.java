package financeos.core;
import java.util.*;
public final class CategoryMaintenance {
 public static CategorySections defaults(CategorySections previous){
  var templates=new LinkedHashMap<String,List<String>>();
  templates.put("Питание",List.of("Продукты","Вкусняшки"));templates.put("Жильё",List.of("Дом","Коммунальные"));templates.put("Поездки",List.of("Проезд","Такси"));templates.put("Машина",List.of("Бензин","ТО машина"));templates.put("Здоровье",List.of("Лекарства"));templates.put("Досуг",List.of("Развлечения"));templates.put("Прочее",List.of("Прочее"));templates.put("Доходы",List.of("Зарплата","Премия","Подработка","Проценты","Возврат","Подарок"));
  var result=previous;
  for(var section:templates.entrySet()){
   if(result.allSections().stream().noneMatch(s->CategorySections.key(s).equals(CategorySections.key(section.getKey()))))result=result.create(section.getKey());
   for(var category:section.getValue()){
    String key=CategorySections.key(result.canonical(category));boolean exists=false;for(var assigned:result.assignments().keySet())if(CategorySections.key(result.canonical(assigned)).equals(key))exists=true;
    if(!exists&&!result.hidden(category))result=result.assign(category,section.getKey());
   }
  }
  return new CategorySections(result.names(),result.assignments(),result.aliases(),result.archived(),"READY");
 }
 public static CategorySections prune(CategorySections config,Collection<String> expenses,Set<String> used){
  var keep=new HashSet<String>();for(var name:used)keep.add(CategorySections.key(config.canonical(name)));
  var next=config;for(var category:expenses)if(!keep.contains(CategorySections.key(config.canonical(category))))next=next.archive(category);
  var active=new HashSet<String>();for(var category:expenses)if(!next.hidden(category)||keep.contains(CategorySections.key(next.canonical(category))))active.add(next.section(category));
  for(var name:List.copyOf(next.names()))if(!CategorySections.key(name).equals(CategorySections.key("Доходы"))&&!CategorySections.key(name).equals(CategorySections.key(CategorySections.OTHER))&&!active.contains(name))next=next.deleteSection(name);
  return new CategorySections(next.names(),next.assignments(),next.aliases(),next.archived(),"READY");
 }
}
