use rand_core::OsRng;
use zeroize::Zeroizing;

use crate::*;

#[test]
fn test_polyseed() {
    struct Vector {
        language: Language,
        seed: String,
        entropy: String,
        birthday: u64,
        has_prefix: bool,
        has_accent: bool,
    }

    #[allow(clippy::unreadable_literal)]
    let vectors = [Vector {
        language: Language::English,
        seed: "raven tail swear infant grief assist regular lamp \
      duck valid someone little harsh puppy airport language"
            .into(),
        entropy: "dd76e7359a0ded37cd0ff0f3c829a5ae01673300000000000000000000000000".into(),
        birthday: 1638446400,
        has_prefix: true,
        has_accent: false,
    }];

    for vector in vectors {
        let add_whitespace = |mut seed: String| {
            seed.push(' ');
            seed
        };

        let seed_without_accents = |seed: &str| {
            seed.split_whitespace()
                .map(|w| w.chars().filter(char::is_ascii).collect::<String>())
                .collect::<Vec<_>>()
                .join(" ")
        };

        let trim_seed = |seed: &str| {
            let seed_to_trim = if vector.has_accent {
                seed_without_accents(seed)
            } else {
                seed.to_owned()
            };
            seed_to_trim
                .split_whitespace()
                .map(|w| {
                    let mut ascii = 0;
                    let mut to_take = w.len();
                    for (i, char) in w.chars().enumerate() {
                        if char.is_ascii() {
                            ascii += 1;
                        }
                        if ascii == PREFIX_LEN {
                            // +1 to include this character, which put us at the prefix length
                            to_take = i + 1;
                            break;
                        }
                    }
                    w.chars().take(to_take).collect::<String>()
                })
                .collect::<Vec<_>>()
                .join(" ")
        };

        // String -> Seed
        let seed =
            Polyseed::from_string(vector.language, Zeroizing::new(vector.seed.clone())).unwrap();
        let trim = trim_seed(&vector.seed);
        let add_whitespace = add_whitespace(vector.seed.clone());
        let seed_without_accents = seed_without_accents(&vector.seed);

        // Make sure a version with added whitespace still works
        let whitespaced_seed =
            Polyseed::from_string(vector.language, Zeroizing::new(add_whitespace)).unwrap();
        assert_eq!(seed, whitespaced_seed);
        // Check trimmed versions works
        if vector.has_prefix {
            let trimmed_seed =
                Polyseed::from_string(vector.language, Zeroizing::new(trim)).unwrap();
            assert_eq!(seed, trimmed_seed);
        }
        // Check versions without accents work
        if vector.has_accent {
            let seed_without_accents =
                Polyseed::from_string(vector.language, Zeroizing::new(seed_without_accents))
                    .unwrap();
            assert_eq!(seed, seed_without_accents);
        }

        let entropy = Zeroizing::new(hex::decode(vector.entropy).unwrap().try_into().unwrap());
        assert_eq!(*seed.entropy(), entropy);
        assert!(seed.birthday().abs_diff(vector.birthday) < TIME_STEP);

        // Entropy -> Seed
        let from_entropy = Polyseed::from(vector.language, 0, seed.birthday(), entropy).unwrap();
        assert_eq!(seed.to_string(), from_entropy.to_string());

        // Check against ourselves
        {
            let seed = Polyseed::new(&mut OsRng, vector.language);
            assert_eq!(
                seed,
                Polyseed::from_string(vector.language, seed.to_string()).unwrap()
            );
            assert_eq!(
                seed,
                Polyseed::from(vector.language, 0, seed.birthday(), seed.entropy().clone(),)
                    .unwrap()
            );
        }
    }
}

#[test]
fn test_invalid_polyseed() {
    // This seed includes unsupported features bits and should error on decode
    let seed = "include domain claim resemble urban hire lunch bird \
    crucial fire best wife ring warm ignore model"
        .into();
    let res = Polyseed::from_string(Language::English, Zeroizing::new(seed));
    assert_eq!(res, Err(PolyseedError::UnsupportedFeatures));
}

#[test]
fn test_key() {
    let seed = "comic blanket chair inject end snow rural improve cereal \
     better initial replace ribbon brother gather unaware"
        .into();
    let res = Polyseed::from_string(Language::English, Zeroizing::new(seed)).unwrap();
    let key = res.key();
    assert_eq!(
        *key,
        [
            216, 82, 37, 164, 252, 122, 170, 61, 52, 152, 131, 26, 181, 226, 191, 131, 204, 3, 242,
            225, 229, 175, 37, 151, 18, 143, 53, 175, 136, 17, 47, 126
        ]
    );
}
