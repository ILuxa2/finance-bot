from app.calculations import category_exists, normalize_category_name


def test_category_name_is_trimmed_and_internal_spaces_are_collapsed():
    assert normalize_category_name("  Домашние   расходы  ") == "Домашние расходы"


def test_category_duplicate_check_is_case_insensitive():
    assert category_exists(["Еда", "Транспорт"], "  еДА ") is True
    assert category_exists(["Еда", "Транспорт"], "Здоровье") is False


def test_empty_category_is_not_a_duplicate():
    assert category_exists(["Еда"], "   ") is False

