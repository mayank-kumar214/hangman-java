import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;

/**
 * Main entry point for the Hangman Application.
 */
public class Hangman {
    public static void main(String[] args) {
        HangmanGame game = new HangmanGame();
        game.start();
    }
}

/**
 * A simple data class to hold a word and its definition.
 */
class Word implements Serializable {
    private static final long serialVersionUID = 1L;
    final String text;
    final String definition;

    public Word(String text, String definition) {
        this.text = text.toUpperCase();
        this.definition = definition;
    }
}

// Helper classes to map the JSON response from Wordnik API
class WordnikWord { String word; }
class WordnikDefinition { String text; }

/**
 * Handles fetching random words and their definitions using the Wordnik API.
 * UPDATED: Now uses Gson for robust JSON parsing.
 */
class WordFetcher {
    private final HttpClient client;
    private final Gson gson;
    private final Random random = new Random();
    private static final String API_KEY = "oltf782tcke00h28uyqzyzf9t8ury0a8dlad83cnb8lta4uog";
    private static final String[] FALLBACK_WORDS = {
            "DEVELOPER:A person that writes computer software.",
            "SUNSHINE:Direct sunlight unbroken by cloud.",
            "BICYCLE:A vehicle with two wheels.",
            "MOUNTAIN:A large natural elevation of the earth's surface.",
            "OCEAN:A very large expanse of sea.",
            "GUITAR:A stringed musical instrument."
    };

    public WordFetcher() {
        this.client = HttpClient.newHttpClient();
        this.gson = new Gson();
    }

    public Word getWord() {
        try {
            String randomWordText = getRandomWord();
            if (randomWordText == null) {
                return getFallbackWord("Failed to get random word from API.");
            }

            String definitionText = getDefinition(randomWordText);
            if (definitionText == null) {
                return getFallbackWord("Failed to get definition for word: " + randomWordText);
            }

            return new Word(randomWordText, definitionText);

        } catch (Exception e) {
            System.out.println("-> API request failed. See error details below:");
            e.printStackTrace();
            return getFallbackWord("Network error or timeout.");
        }
    }

    private String getRandomWord() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.wordnik.com/v4/words.json/randomWord" +
                        "?hasDictionaryDef=true&minCorpusCount=5000&minLength=5&maxLength=12&api_key=" + API_KEY))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("Random word API failed with status: " + response.statusCode());
            System.out.println("Response body: " + response.body()); // For debugging
            return null;
        }

        WordnikWord wordnikWord = gson.fromJson(response.body(), WordnikWord.class);
        return (wordnikWord != null && wordnikWord.word != null && wordnikWord.word.matches("[a-zA-Z]+")) ? wordnikWord.word : null;
    }

    private String getDefinition(String word) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.wordnik.com/v4/word.json/" + word.toLowerCase() +
                        "/definitions?limit=1&includeRelated=false&sourceDictionaries=wiktionary,wordnet&useCanonical=false&api_key=" + API_KEY))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("Definition API failed for '" + word + "' with status: " + response.statusCode());
            System.out.println("Response body: " + response.body()); // For debugging
            return null;
        }
        
        Type definitionListType = new TypeToken<List<WordnikDefinition>>(){}.getType();
        List<WordnikDefinition> definitions = gson.fromJson(response.body(), definitionListType);

        if (definitions != null && !definitions.isEmpty() && definitions.get(0).text != null) {
            String definition = definitions.get(0).text;
            definition = definition.replaceAll("<[^>]+>", "").trim();
            if (!definition.isEmpty()) {
                return Character.toUpperCase(definition.charAt(0)) + definition.substring(1);
            }
        }
        
        return null;
    }

    private Word getFallbackWord(String reason) {
        System.out.println("-> " + reason + " Using a fallback word.");
        String randomEntry = FALLBACK_WORDS[random.nextInt(FALLBACK_WORDS.length)];
        String[] parts = randomEntry.split(":", 2);
        return new Word(parts[0], parts[1]);
    }
}

/**
 * The main controller for the Hangman game.
 */
class HangmanGame {
    private final Scanner scanner;
    private final WordFetcher wordFetcher;
    private boolean isRunning;
    private static final int MAX_WRONG_GUESSES = 6;
    private static final String[] HANGMAN_STAGES = {
            "  +---+\n  |   |\n      |\n      |\n      |\n      |\n=========",
            "  +---+\n  |   |\n  O   |\n      |\n      |\n      |\n=========",
            "  +---+\n  |   |\n  O   |\n  |   |\n      |\n      |\n=========",
            "  +---+\n  |   |\n  O   |\n /|   |\n      |\n      |\n=========",
            "  +---+\n  |   |\n  O   |\n /|\\  |\n      |\n      |\n=========",
            "  +---+\n  |   |\n  O   |\n /|\\  |\n /    |\n      |\n=========",
            "  +---+\n  |   |\n  O   |\n /|\\  |\n / \\  |\n      |\n========="
    };

    public HangmanGame() {
        this.scanner = new Scanner(System.in);
        this.wordFetcher = new WordFetcher();
        this.isRunning = true;
    }

    public void start() {
        displayWelcome();
        while (isRunning) {
            runGameSession();
            askToPlayAgain();
        }
        System.out.println("\nThanks for playing!");
        scanner.close();
    }

    private void runGameSession() {
        System.out.println("\nFetching a new word from Wordnik API...");
        Word word = wordFetcher.getWord();

        String secretWord = word.text;
        char[] guessedProgress = new char[secretWord.length()];
        Arrays.fill(guessedProgress, '_');
        Set<Character> usedLetters = new HashSet<>();
        int wrongGuesses = 0;

        System.out.println("Let's begin! The word has " + secretWord.length() + " letters.");

        while (wrongGuesses < MAX_WRONG_GUESSES) {
            displayGameState(wrongGuesses, guessedProgress, usedLetters);
            String input = getUserInput();

            if ("HINT".equals(input)) {
                System.out.println("--> Hint (Definition): " + word.definition);
                wrongGuesses++;
                continue;
            }

            if (input.length() > 1) {
                if (input.equals(secretWord)) {
                    displayWin(secretWord, word.definition);
                    return;
                } else {
                    System.out.println("--> Sorry, that's not the right word.");
                    wrongGuesses++;
                }
            } else {
                char letter = input.charAt(0);
                if (usedLetters.contains(letter)) {
                    System.out.println("--> You've already tried that letter!");
                    continue;
                }
                usedLetters.add(letter);
                if (secretWord.indexOf(letter) >= 0) {
                    System.out.println("--> Good guess!");
                    updateGuessedProgress(secretWord, guessedProgress, letter);
                    if (new String(guessedProgress).equals(secretWord)) {
                        displayWin(secretWord, word.definition);
                        return;
                    }
                } else {
                    System.out.println("--> Sorry, that letter is not in the word.");
                    wrongGuesses++;
                }
            }
        }
        displayLoss(secretWord, word.definition);
    }

    private void updateGuessedProgress(String secretWord, char[] progress, char letter) {
        for (int i = 0; i < secretWord.length(); i++) {
            if (secretWord.charAt(i) == letter) {
                progress[i] = letter;
            }
        }
    }

    private String getUserInput() {
        while (true) {
            System.out.print("\nEnter your guess (a letter, a word, or 'hint'): ");
            if (scanner.hasNextLine()) {
                String input = scanner.nextLine().trim().toUpperCase();
                if (!input.isEmpty() && (input.equals("HINT") || input.matches("[A-Z]+"))) {
                    return input;
                }
                System.out.println("Invalid input. Please try again.");
            } else {
                System.out.println("\nInput stream closed. Exiting game.");
                System.exit(0);
                return "";
            }
        }
    }

    private void askToPlayAgain() {
        while (true) {
            System.out.print("\nDo you want to play again? (y/n): ");
            if (scanner.hasNextLine()) {
                String response = scanner.nextLine().trim().toLowerCase();
                if ("y".equals(response) || "yes".equals(response)) {
                    return;
                } else if ("n".equals(response) || "no".equals(response)) {
                    isRunning = false;
                    return;
                } else {
                    System.out.println("Invalid input. Please enter 'y' or 'n'.");
                }
            } else {
                System.out.println("\nInput stream closed. Exiting game.");
                System.exit(0);
            }
        }
    }

    private void displayWelcome() {
        System.out.println("========================================");
        System.out.println("        WELCOME TO HANGMAN");
        System.out.println("      Powered by Wordnik API");
        System.out.println("========================================");
    }

    private void displayGameState(int wrongGuesses, char[] progress, Set<Character> used) {
        System.out.println("\n" + "-".repeat(40));
        System.out.println(HANGMAN_STAGES[wrongGuesses]);
        System.out.print("\nWord: ");
        for (char c : progress) System.out.print(c + " ");
        System.out.println();
        System.out.print("Used letters: ");
        if (used.isEmpty()) {
            System.out.println("None");
        } else {
            new TreeSet<>(used).forEach(letter -> System.out.print(letter + " "));
            System.out.println();
        }
        System.out.println("Wrong Guesses: " + wrongGuesses + " of " + MAX_WRONG_GUESSES);
    }

    private void displayWin(String word, String definition) {
        System.out.println("\n========================================");
        System.out.println("   CONGRATULATIONS! YOU WON!");
        System.out.println("   The word was: " + word);
        System.out.println("   Definition: " + definition);
        System.out.println("========================================");
    }

    private void displayLoss(String word, String definition) {
        System.out.println("\n" + HANGMAN_STAGES[MAX_WRONG_GUESSES]);
        System.out.println("========================================");
        System.out.println("          GAME OVER! You lost.");
        System.out.println("   The word was: " + word);
        System.out.println("   Definition: " + definition);
        System.out.println("========================================");
    }
}