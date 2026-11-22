pub fn category(name: &str) -> i32 {
    let name=name.to_lowercase();let mut result=0;
    if name.contains("обход") || name.contains("bypass") {result|=2;}
    if name.contains("игров") || name.contains("gaming") {result|=4;}
    if name.contains("lte") {result|=8;}
    if result==0 {1} else {result}
}
pub fn shown(name: &str, endpoint: &str, category: i32, favorite: bool, filter: i32, search: &str) -> bool {
    let category_matches=match filter {1=>category&1!=0,2=>category&2!=0,3=>category&4!=0,4=>category&8!=0,5=>favorite,_=>true};
    let search=search.trim().to_lowercase();
    category_matches && (search.is_empty() || name.to_lowercase().contains(&search) || endpoint.to_lowercase().contains(&search))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn auto_bypass_and_lte_are_not_regular_servers() {
        assert_eq!(category("Авто-регион"),1);
        assert_eq!(category("Авто-обход 3-5x"),2);
        assert_eq!(category("Германия Игровой"),4);
        assert_eq!(category("Швеция №1 LTE 1.5x"),8);
    }
    #[test]
    fn filters_preserve_search_and_favorite_constraints() {
        assert!(!shown("Авто-обход 3-5x","example.org:443",2,false,1,""));
        assert!(shown("Нидерланды","example.org:443",1,true,5,"НИДЕР"));
        assert!(!shown("Нидерланды","example.org:443",1,false,5,""));
        assert!(shown("Нидерланды","example.org:443",1,false,0,"EXAMPLE"));
        assert!(!shown("Германия","example.org:443",1,true,0,"Швеция"));
    }
}
