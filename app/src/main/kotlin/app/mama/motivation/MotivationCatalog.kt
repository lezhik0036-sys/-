package app.mama.motivation

/*
 * Local motivational phrases (DESIGN_SYSTEM_V1 §13). No network, no
 * personal data. Shown inside the existing screens (scheduled series,
 * ACTIVE, lock, success, failure / recovery, review, "Цена слова", FLEX),
 * never as a separate step.
 *
 * Tone: calm support. No shame, pressure, comparison or promised results;
 * nothing about money or the partial return of "Цена слова".
 */

/** Approved categories. */
enum class MotivationCategory {
    CALM, DISCIPLINE, CONFIDENCE, RECOVERY, SLEEP, MORNING, FOCUS, SECOND_CHANCE, LONG_TERM, SELF_RESPECT,
}

/**
 * Future personalisation ("Кто ты сегодня?", FUTURE). The pilot has no role
 * choice: the profile is always [GENERAL].
 */
enum class ProfileType { ATHLETE, ENTREPRENEUR, PARENT, PROFESSIONAL, STUDENT, CREATOR, GENERAL }

/** One phrase. Empty [profiles] = for everyone; otherwise only for those profiles (none in the pilot). */
data class Phrase(
    val id: String,
    val text: String,
    val category: MotivationCategory,
    val profiles: Set<ProfileType> = emptySet(),
) {
    fun fits(profile: ProfileType): Boolean = profiles.isEmpty() || profile in profiles
}

/** Where a phrase is shown, and which categories fit there. */
enum class Placement(vararg categories: MotivationCategory) {
    SCHEDULED(MotivationCategory.CONFIDENCE, MotivationCategory.CALM, MotivationCategory.LONG_TERM),
    ACTIVE(MotivationCategory.DISCIPLINE, MotivationCategory.CONFIDENCE, MotivationCategory.LONG_TERM, MotivationCategory.SELF_RESPECT),
    LOCK_NIGHT(MotivationCategory.SLEEP, MotivationCategory.CALM, MotivationCategory.SELF_RESPECT),
    LOCK_DAY(MotivationCategory.FOCUS, MotivationCategory.CALM, MotivationCategory.DISCIPLINE),
    MORNING(MotivationCategory.MORNING, MotivationCategory.CONFIDENCE),
    SUCCESS(MotivationCategory.CONFIDENCE, MotivationCategory.SELF_RESPECT, MotivationCategory.LONG_TERM),

    /** Failure / recovery: only recovery and a second chance. */
    RECOVERY(MotivationCategory.RECOVERY, MotivationCategory.SECOND_CHANCE),
    REVIEW(MotivationCategory.SELF_RESPECT, MotivationCategory.LONG_TERM, MotivationCategory.CONFIDENCE),
    COMMITMENT(MotivationCategory.DISCIPLINE, MotivationCategory.SELF_RESPECT),
    FLEX(MotivationCategory.FOCUS, MotivationCategory.DISCIPLINE, MotivationCategory.LONG_TERM),
    ;

    val categories: List<MotivationCategory> = categories.toList()
}

object MotivationCatalog {

    val phrases: List<Phrase> = buildList {
        fun group(category: MotivationCategory, vararg texts: String) {
            val prefix = category.name.lowercase().replace('_', '-')
            texts.forEachIndexed { i, t -> add(Phrase("$prefix-%02d".format(i + 1), t, category)) }
        }
        group(
            MotivationCategory.CALM,
            "Желание проверить телефон пройдёт. Твоё решение останется.",
            "Сейчас не нужно ничего проверять. Можно просто побыть здесь.",
            "Тишина — это не пустота. Это место для тебя.",
            "Выдохни. Всё важное никуда не денется.",
            "Спокойствие приходит, когда перестаёшь догонять ленту.",
            "Ничего не случится, если ответить позже.",
            "Этот момент принадлежит тебе, а не уведомлениям.",
            "Тревога пройдёт. Телефон подождёт.",
            "Медленнее — тоже нормально.",
            "Мир может пару часов обойтись без тебя. Ты без него — тоже.",
            "Пауза — это забота о себе, а не потеря.",
            "Здесь и сейчас — достаточно.",
        )
        group(
            MotivationCategory.DISCIPLINE,
            "Ты уже принял решение. Теперь просто останься ему верен.",
            "Дисциплина — это обещание, которое держишь, даже когда никто не смотрит.",
            "Не нужно быть сильным весь день. Достаточно этого часа.",
            "Порядок начинается с одного выполненного решения.",
            "Решение уже принято. Осталось его не отменять.",
            "Каждый раз, когда телефон остаётся в стороне, привычка слабеет.",
            "Сила — в повторении, а не в рывке.",
            "Сделай то, что решил, — и этого уже достаточно.",
            "Правило, которое выбираешь сам, легче держать.",
            "Ты не борешься с телефоном. Ты выполняешь своё решение.",
            "Маленькое правило, соблюдённое сегодня, сильнее большого плана на завтра.",
            "Импульс длится минуту. Решение — дольше.",
            "Когда решение имеет вес, его легче удержать.",
        )
        group(
            MotivationCategory.CONFIDENCE,
            "Уверенность растёт из маленьких выполненных обещаний.",
            "Ты уже доказываешь себе, что можешь.",
            "Каждый выдержанный час — твой аргумент в пользу себя.",
            "Ты можешь больше, чем кажется в момент сомнения.",
            "Твоё слово начинает весить больше. Прежде всего для тебя.",
            "Ты держишь курс — и это заметно.",
            "Доверие к себе строится из таких дней.",
            "Сомнение — не сигнал остановиться. Это просто мысль.",
            "Ты выбираешь, на что тратить внимание. Это и есть сила.",
            "Ты справляешься лучше, чем думаешь.",
            "Каждое сдержанное обещание делает следующее легче.",
            "Тебе не нужно разрешение, чтобы выбирать себя.",
            "Когда есть опора, держаться курса легче.",
        )
        group(
            MotivationCategory.RECOVERY,
            "Срыв — это часть пути.",
            "Одна попытка ничего не решает.",
            "Сегодня получилось не всё. Но решение всё ещё твоё.",
            "Этот путь не закончился.",
            "Не идеальность меняет жизнь, а возвращение к пути.",
            "Один сложный день не стирает твой прогресс.",
            "Ошибка — это информация, а не приговор.",
            "Можно споткнуться и идти дальше. Так ходят все.",
            "Отнесись к себе так же бережно, как к близкому человеку в трудный день.",
            "Сделанные шаги остаются с тобой.",
            "Важно не то, что было вчера, а то, что ты выберешь дальше.",
            "Тебе не нужно быть идеальным. Тебе нужно продолжать.",
        )
        group(
            MotivationCategory.SLEEP,
            "Хороший сон начинается с отложенного телефона.",
            "Ночь — для отдыха, а не для ленты.",
            "Утро скажет спасибо за этот вечер.",
            "Экран погас. Пусть и мысли станут тише.",
            "Всё, что пришло ночью, можно прочитать утром.",
            "Сон — лучшее, что можно сделать для завтрашнего дня.",
            "Отдых — не потерянное время. Это время, которое возвращает силы.",
            "Позволь себе выспаться. Ты это заслуживаешь.",
            "Темнота за окном — знак отдыхать, а не листать.",
            "Чем тише вечер, тем яснее утро.",
            "Тело знает, как отдыхать. Просто не мешай ему.",
            "Спокойной ночи. Телефон отдыхает вместе с тобой.",
            "Ночь — время восстановить себя.",
        )
        group(
            MotivationCategory.MORNING,
            "Доброе утро. Новый день начинается с тебя, а не с уведомлений.",
            "Первые минуты утра — твои. Не отдавай их ленте.",
            "Утро без спешки — маленький подарок себе.",
            "Начни день с того, что важно тебе.",
            "Новый день — новая возможность держать слово.",
            "Ясная голова утром — результат спокойного вечера.",
            "Сначала — ты. Потом — телефон.",
            "Этот день ещё никто не написал. Можно начать спокойно.",
            "Утро любит тишину.",
            "Посмотри в окно раньше, чем в экран.",
            "Хорошее утро — продолжение хорошего решения.",
        )
        group(
            MotivationCategory.FOCUS,
            "Ты не отказываешься от телефона. Ты возвращаешь себе время.",
            "Каждая минута вне телефона возвращает тебя к себе.",
            "Одно дело за раз. Этого достаточно.",
            "Внимание — самое ценное, что у тебя есть. Сейчас оно твоё.",
            "Глубокая работа начинается там, где заканчиваются уведомления.",
            "Мир подождёт, пока ты делаешь главное.",
            "Сосредоточенность — это когда ничто не тянет в сторону.",
            "Минуты без отвлечений складываются в результат.",
            "Ты здесь ради дела, а не ради ленты.",
            "Пусть сейчас будет только то, что важно.",
            "Фокус — это не напряжение. Это тишина вокруг главного.",
        )
        group(
            MotivationCategory.SECOND_CHANCE,
            "Начать снова — тоже форма силы.",
            "Ты можешь начать снова.",
            "Новая попытка — не откат, а новый шанс стать ближе к себе.",
            "Каждый новый старт уже знает больше, чем прошлый.",
            "Второй шанс — это не слабость. Это смелость.",
            "Путь продолжается с того места, где ты сейчас.",
            "Возвращаться — нормально. Важно, что ты возвращаешься.",
            "Сегодня можно начать заново — спокойно и без спешки.",
            "Каждое утро — возможность снова выбрать себя.",
            "Решение снова в твоих руках. Как и всегда.",
            "Попробовать ещё раз — это выбор, а не обязанность.",
        )
        group(
            MotivationCategory.LONG_TERM,
            "Большие перемены складываются из маленьких решений.",
            "Ты строишь привычку, а не проходишь испытание.",
            "Через месяц ты поблагодаришь себя за эти дни.",
            "Неважно, как быстро. Важно, что в нужную сторону.",
            "Каждый день без лишнего экрана — шаг к новому ритму.",
            "Стабильные шаги приводят к свободе.",
            "Ты меняешь не один вечер, а образ жизни.",
            "Хорошие привычки незаметны. Заметен их результат.",
            "Путь в семь дней начинается с одного.",
            "Ты не ограничиваешь себя — ты выбираешь себя.",
            "Сегодняшнее решение — подарок тому, кем ты становишься.",
        )
        group(
            MotivationCategory.SELF_RESPECT,
            "Держать слово, данное себе, — это уважение к себе.",
            "Твоё время стоит того, чтобы его беречь.",
            "Ты заслуживаешь внимания — прежде всего своего.",
            "Забота о себе — не эгоизм.",
            "Твоё решение важно, даже если о нём знаешь только ты.",
            "Граница, поставленная для себя, — это сила, а не запрет.",
            "Спокойные вечера — то, что ты заслуживаешь.",
            "Уважай своё «нет» так же, как чужое.",
            "Твоё спокойствие — подарок близким.",
            "Ты важнее любого уведомления.",
            "Беречь себя — тоже решение. И оно твоё.",
        )
    }

    private val byId: Map<String, Phrase> = phrases.associateBy { it.id }

    fun byId(id: String): Phrase? = byId[id]

    fun byCategory(category: MotivationCategory): List<Phrase> = phrases.filter { it.category == category }

    /**
     * Phrases for [placement]; a profile's own phrases first, then the
     * general ones. [maxLength]: only short ones, where a single line fits
     * (all of them if none is that short).
     */
    fun candidates(placement: Placement, profile: ProfileType = ProfileType.GENERAL, maxLength: Int = Int.MAX_VALUE): List<Phrase> {
        val all = phrases.filter { it.category in placement.categories && it.fits(profile) }
        val fitting = all.filter { it.text.length <= maxLength }.ifEmpty { all }
        return fitting.filter { it.profiles.isNotEmpty() } + fitting.filter { it.profiles.isEmpty() }
    }

    /**
     * A phrase for [placement], chosen by [key] (same key, same phrase) and
     * avoiding the [recent] ids while there is anything else to show.
     */
    fun pick(
        placement: Placement,
        key: String,
        profile: ProfileType = ProfileType.GENERAL,
        recent: List<String> = emptyList(),
        maxLength: Int = Int.MAX_VALUE,
    ): Phrase {
        val all = candidates(placement, profile, maxLength)
        val fresh = all.filter { it.id !in recent }
        val pool = fresh.ifEmpty {
            // Everything was shown lately: the one shown longest ago.
            listOf(all.maxBy { recent.indexOf(it.id) })
        }
        return pool[Math.floorMod("${placement.name}:$key".hashCode(), pool.size)]
    }
}
