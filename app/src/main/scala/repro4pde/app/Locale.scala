package repro4pde.app

object Locale {
  val defaultLocale = new Locale {
    val enableComparison = "Enable comparison with current code";
    val disableComparison = "Disable comparison with current code";
    val secound = "s";
    val deleted = "deleted";
    val created = "created";
    val changed = "changed";
    val unchanged = "unchanged";
    val slaveError = "An error occurred in the past sketch";
    val reload = "Reload";
    val regenerateState = "Regenerate random seed";
  };
  val locales = Map(
    "en" -> defaultLocale,
    "ja" -> new Locale {
      val enableComparison = "現在のコードとの比較を有効化";
      val disableComparison = "現在のコードとの比較を無効化";
      val secound = "秒";
      val deleted = "削除";
      val created = "作成";
      val changed = "変更";
      val unchanged = "変更がありません";
      val slaveError = "過去のスケッチでエラーが発生しました";
      val reload = "再読み込み";
      val regenerateState = "乱数を再生成";
    }
  );

  def getLocale(lang: String): Locale = {
    locales.getOrElse(lang, defaultLocale);
  }
}

abstract class Locale {
  val enableComparison: String;
  val disableComparison: String;
  val secound: String;
  val deleted: String;
  val created: String;
  val changed: String;
  val unchanged: String;
  val slaveError: String;
  val reload: String;
  val regenerateState: String;
}
