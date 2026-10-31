package kr.poketrainer;

import java.util.List;

/**
 * The road of a player's trainer: the RCT gym leaders, elite four and champions of each region in challenge order.
 * id: the RCT trainer id (its team comes from the RCT data pack), name: Korean name shown to players.
 */
public final class Stages {
    public record Stage(String id, String name, String region, String role) {
        public boolean champion() {
            return role.equals("챔피언");
        }
    }

    public static final List<Stage> ALL = List.of(
        gym("kanto_brock", "웅", "관동"), gym("kanto_misty", "이슬", "관동"), gym("kanto_ltsurge", "마티스", "관동"),
        gym("kanto_erika", "민화", "관동"), gym("kanto_koga", "독수", "관동"), gym("kanto_sabrina", "초련", "관동"),
        gym("kanto_blaine", "강연", "관동"), gym("kanto_giovanni", "비주기", "관동"),
        elite("kanto_league_lorelei", "칸나", "관동"), elite("kanto_league_bruno", "시바", "관동"),
        elite("kanto_league_agatha", "국화", "관동"), elite("kanto_league_lance", "목호", "관동"),
        champion("kanto_champion_blue", "그린", "관동"),

        gym("johto_valerio", "비상", "성도"), gym("johto_raffaello", "호일", "성도"), gym("johto_chiara", "꼭두", "성도"),
        gym("johto_angelo", "유빈", "성도"), gym("johto_furio", "사도", "성도"), gym("johto_jasmine", "규리", "성도"),
        gym("johto_alfredo", "류옹", "성도"), gym("johto_sandra", "이향", "성도"),
        elite("johto_league_pino", "일목", "성도"), elite("johto_league_koga", "독수", "성도"),
        elite("johto_league_bruno", "시바", "성도"), elite("johto_league_karen", "카렌", "성도"),
        champion("johto_champion_lance", "목호", "성도"),

        gym("hoenn_petra", "원규", "호연"), gym("hoenn_rudi", "철구", "호연"), gym("hoenn_walter", "암페어", "호연"),
        gym("hoenn_fiammetta", "민지", "호연"), gym("hoenn_norman", "종길", "호연"), gym("hoenn_alice", "은송", "호연"),
        gym("hoenn_tell", "풍&란", "호연"), gym("hoenn_adriano", "아단", "호연"),
        elite("hoenn_league_fosco", "혁진", "호연"), elite("hoenn_league_ester", "회연", "호연"),
        elite("hoenn_league_frida", "미혜", "호연"), elite("hoenn_league_drake", "권수", "호연"),
        champion("hoenn_champion_rocco", "성호", "호연"),

        gym("sinnoh_pedro", "강석", "신오"), gym("sinnoh_gardenia", "유채", "신오"), gym("sinnoh_marzia", "자두", "신오"),
        gym("sinnoh_omar", "맥실러", "신오"), gym("sinnoh_fannie", "멜리사", "신오"), gym("sinnoh_ferruccio", "동관", "신오"),
        gym("sinnoh_bianca", "무청", "신오"), gym("sinnoh_corrado", "전진", "신오"),
        elite("sinnoh_league_aaron", "충호", "신오"), elite("sinnoh_league_terrie", "들국화", "신오"),
        elite("sinnoh_league_vulcano", "대엽", "신오"), elite("sinnoh_league_luciano", "오엽", "신오"),
        champion("sinnoh_champion_camilla", "난천", "신오"),

        gym("pokemon_trainer_cedric_03961", "체렌", "하나"),
        gym("cyclist_kayla_03011", "보미카", "하나"),
        gym("artist_ismael_04811", "아티", "하나"),
        gym("lady_kylie_048b1", "카밀레", "하나"),
        gym("worker_braden_04601", "야콘", "하나"),
        gym("pokemon_ranger_allison_03371", "풍란", "하나"),
        gym("dragon_tamer_andre_03881", "사간", "하나"),
        gym("title_defense_marlon_05eb1", "시즈", "하나"),
        elite("madame_rebecca_04881", "망초", "하나"),
        elite("black_belt_haugh_022a", "연무", "하나"),
        elite("rich_boy_jason_032b1", "블래리", "하나"),
        elite("parasol_lady_alexa_03e11", "카틀레야", "하나"),
        champion("poke_kid_ariel_04441", "아이리스", "하나"),

        gym("cyclist_kayla_03012", "비올라", "칼로스"),
        gym("jogger_craig_03ce1", "자크로", "칼로스"),
        gym("battle_girl_helen_03081", "코르니", "칼로스"),
        gym("pokemon_breeder_albert_03101", "후쿠지", "칼로스"),
        gym("scientist_beau_01541", "시트론", "칼로스"),
        gym("lady_kylie_048b2", "마슈", "칼로스"),
        gym("parasol_lady_alexa_03e12", "고지카", "칼로스"),
        gym("skier_bjorn_03561", "우루프", "칼로스"),
        elite("trainer_shelly_00111", "파키라", "칼로스"),
        elite("swimmer_adrian_035e1", "즈미", "칼로스"),
        elite("mega_trainer_aloysius_05cc1", "간피", "칼로스"),
        elite("madame_rebecca_04882", "드라세나", "칼로스"),
        champion("lady_jacki_020", "카르네", "칼로스"),

        gym("pokemon_trainer_lucas_04cd1", "일리마", "알로라"),
        gym("swimmer_aubree_03751", "수련", "알로라"),
        gym("ninja_boy_antonio_03591", "카키", "알로라"),
        gym("pokemon_trainer_dawn_04d01", "마오", "알로라"),
        gym("scientist_beau_015410", "마마네", "알로라"),
        gym("poke_kid_ariel_04442", "마쇼", "알로라"),
        gym("pi_carlos_03461", "크치나", "알로라"),
        gym("pokemon_ranger_allison_033710", "하푸우", "알로라"),
        elite("veteran_armando_03fb1", "할라", "알로라"),
        elite("hoenn_liris", "라이치", "알로라"),
        elite("poke_kid_ariel_04443", "아세로라", "알로라"),
        elite("skier_andrea_03521", "카히리", "알로라"),
        champion("pokemon_ranger_deshawn_04b91", "쿠쿠이", "알로라"),

        gym("worker_braden_046010", "야로", "가라르"),
        gym("swimmer_aubree_037510", "야청", "가라르"),
        gym("veteran_armando_03fb10", "순무", "가라르"),
        gym("battle_girl_helen_03082", "채두", "가라르"),
        gym("madame_rebecca_04883", "포플러", "가라르"),
        gym("guitarist_arturo_04321", "마쿠와", "가라르"),
        gym("guitarist_arturo_04322", "두송", "가라르"),
        gym("dragon_tamer_andre_038810", "금랑", "가라르"),
        elite("pokemon_trainer_dawn_04d010", "마리", "가라르"),
        elite("pokemon_trainer_cedric_039610", "호브", "가라르"),
        elite("rich_boy_jason_032b2", "비트", "가라르"),
        elite("black_belt_haugh_02b9", "마스타드", "가라르"),
        champion("mega_trainer_aloysius_05cc10", "단델", "가라르"),

        gym("cyclist_kayla_03013", "단풍", "팔데아"),
        gym("artist_ismael_04812", "콜사", "팔데아"),
        gym("poke_kid_ariel_04444", "모야모", "팔데아"),
        gym("swimmer_adrian_035e10", "곤포", "팔데아"),
        gym("collector_brady_03ab1", "청목", "팔데아"),
        gym("trainer_shelly_00112", "라임", "팔데아"),
        gym("parasol_lady_alexa_03e13", "리피", "팔데아"),
        gym("skier_bjorn_03562", "그루샤", "팔데아"),
        elite("pokemon_ranger_allison_033711", "칠리", "팔데아"),
        elite("poke_kid_ariel_04445", "뽀삐", "팔데아"),
        elite("collector_brady_03ab10", "청목", "팔데아"),
        elite("dragon_tamer_andre_038811", "팔자크", "팔데아"),
        champion("lady_kylie_048b3", "오모다카", "팔데아"));

    private Stages() {
    }

    private static Stage gym(String id, String name, String region) {
        return new Stage(id, name, region, "관장");
    }

    private static Stage elite(String id, String name, String region) {
        return new Stage(id, name, region, "사천왕");
    }

    private static Stage champion(String id, String name, String region) {
        return new Stage(id, name, region, "챔피언");
    }

    /** The highest level in a stage's RCT team, or 0 when unknown. */
    public static int aceLevel(Stage stage) {
        com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC trainer = com.gitlab.srcmc.rctmod.ModCommon.RCT.getTrainerRegistry()
            .getById(stage.id(), com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC.class);
        return trainer == null || trainer.getTeam() == null ? 0
            : java.util.Arrays.stream(trainer.getTeam()).mapToInt(com.cobblemon.mod.common.pokemon.Pokemon::getLevel).max().orElse(0);
    }

    /** "관동 관장 웅" */
    public static String label(Stage stage) {
        return stage.region() + " " + stage.role() + " " + stage.name();
    }
}
