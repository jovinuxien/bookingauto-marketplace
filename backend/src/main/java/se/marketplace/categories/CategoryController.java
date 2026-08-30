package se.marketplace.categories;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The list, for a form that has to offer it.
 *
 * <p>Exists because the signup form asks a salon what it sells, and a list a
 * person chooses from has to be the list the server will accept — a copy in
 * the frontend would be a fourth place for the fact ADR 0013 reduced to one.
 * Slug and label only: synonyms are for the matcher and the model, and the
 * URL path is for the landing pages. Nothing here writes.
 */
@RestController
@RequestMapping("/api/categories")
class CategoryController {

	private final Categories categories;

	CategoryController(Categories categories) {
		this.categories = categories;
	}

	@GetMapping
	List<Choice> all() {
		return categories.all().stream()
			.map(category -> new Choice(category.slug(), category.label()))
			.toList();
	}

	record Choice(String slug, String label) {}

}
