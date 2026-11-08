use rand_core::OsRng;
use zeroize::Zeroizing;

use curve25519_dalek::scalar::Scalar;

fn keccak256(input: impl AsRef<[u8]>) -> [u8; 32] {
    monero::cryptonote::hash::Hash::new(input).to_bytes()
}

use crate::*;

#[test]
fn test_original_seed() {
    struct Vector {
        language: Language,
        seed: String,
        spend: String,
        view: String,
    }

    let vectors = [
        Vector {
            language: Language::English,
            seed: "washing thirsty occur lectures tuesday fainted toxic adapt \
               abnormal memoir nylon mostly building shrugged online ember northern \
               ruby woes dauntless boil family illness inroads northern"
                .into(),
            spend: "c0af65c0dd837e666b9d0dfed62745f4df35aed7ea619b2798a709f0fe545403".into(),
            view: "513ba91c538a5a9069e0094de90e927c0cd147fa10428ce3ac1afd49f63e3b01".into(),
        },
        Vector {
            language: Language::English,
            seed: "plus plus plus plus plus plus plus plus \
               plus plus plus plus plus plus plus plus \
               plus plus plus plus plus plus plus plus plus"
                .into(),
            spend: "3b0400003b0400003b0400003b0400003b0400003b0400003b0400003b040000".into(),
            view: "43a8a7715eed11eff145a2024ddcc39740255156da7bbd736ee66a0838053a02".into(),
        },
        Vector {
            language: Language::English,
            seed: "audio audio audio audio audio audio audio audio \
               audio audio audio audio audio audio audio audio \
               audio audio audio audio audio audio audio audio audio"
                .into(),
            spend: "7900000079000000790000007900000079000000790000007900000079000000".into(),
            view: "20bec797ab96780ae6a045dd816676ca7ed1d7c6773f7022d03ad234b581d600".into(),
        },
    ];

    for vector in vectors {
        fn trim_by_lang(word: &str, lang: Language) -> String {
            word.chars()
                .take(LANGUAGES[&lang].unique_prefix_length)
                .collect()
        }

        let trim_seed = |seed: &str| {
            seed.split_whitespace()
                .map(|word| trim_by_lang(word, vector.language))
                .collect::<Vec<_>>()
                .join(" ")
        };

        // Test against Monero
        {
            let seed =
                Seed::from_string(vector.language, Zeroizing::new(vector.seed.clone())).unwrap();
            let trim = trim_seed(&vector.seed);
            assert_eq!(
                seed,
                Seed::from_string(vector.language, Zeroizing::new(trim)).unwrap()
            );

            let spend: [u8; 32] = hex::decode(vector.spend).unwrap().try_into().unwrap();
            // For originalal seeds, Monero directly uses the entropy as a spend key
            assert_eq!(
                Option::<Scalar>::from(Scalar::from_canonical_bytes(*seed.entropy())),
                Option::<Scalar>::from(Scalar::from_canonical_bytes(spend)),
            );

            let view: [u8; 32] = hex::decode(vector.view).unwrap().try_into().unwrap();
            // Monero then derives the view key as H(spend)
            assert_eq!(
                Scalar::from_bytes_mod_order(keccak256(spend)),
                Scalar::from_canonical_bytes(view).unwrap()
            );

            assert_eq!(
                Seed::from_entropy(vector.language, Zeroizing::new(spend)).unwrap(),
                seed
            );
        }

        // Test against ourselves
        {
            let seed = Seed::new(&mut OsRng, vector.language);
            let trim = trim_seed(&seed.to_string());
            assert_eq!(
                seed,
                Seed::from_string(vector.language, Zeroizing::new(trim)).unwrap()
            );
            assert_eq!(
                seed,
                Seed::from_entropy(vector.language, seed.entropy()).unwrap()
            );
            assert_eq!(
                seed,
                Seed::from_string(vector.language, seed.to_string()).unwrap()
            );
        }
    }
}
