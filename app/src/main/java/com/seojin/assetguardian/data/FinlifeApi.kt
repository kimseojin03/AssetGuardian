package com.seojin.assetguardian.data

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 금융감독원 금융상품통합비교공시 Open API
 * https://finlife.fss.or.kr  → 오픈 API → 인증키 신청 (무료, 즉시 발급)
 *
 * 적금: savingProductsSearch.json / 예금: depositProductsSearch.json / 금융회사: companySearch.json
 * topFinGrpNo: 020000 = 은행, 030300 = 저축은행
 */
interface FinlifeApi {
    @GET("savingProductsSearch.json")
    suspend fun savings(
        @Query("auth") auth: String,
        @Query("topFinGrpNo") group: String,
        @Query("pageNo") page: Int
    ): FinlifeResponse

    @GET("depositProductsSearch.json")
    suspend fun deposits(
        @Query("auth") auth: String,
        @Query("topFinGrpNo") group: String,
        @Query("pageNo") page: Int
    ): FinlifeResponse

    /** 은행 홈페이지 주소(homp_url)를 얻기 위한 금융회사 목록 */
    @GET("companySearch.json")
    suspend fun companies(
        @Query("auth") auth: String,
        @Query("topFinGrpNo") group: String,
        @Query("pageNo") page: Int
    ): CompanyResponse

    companion object {
        fun create(): FinlifeApi = Retrofit.Builder()
            .baseUrl("https://finlife.fss.or.kr/finlifeapi/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FinlifeApi::class.java)
    }
}

data class FinlifeResponse(val result: FinlifeResult?)

data class FinlifeResult(
    @SerializedName("err_cd") val errCd: String?,
    @SerializedName("err_msg") val errMsg: String?,
    @SerializedName("total_count") val totalCount: Int?,
    @SerializedName("max_page_no") val maxPageNo: Int?,
    @SerializedName("now_page_no") val nowPageNo: Int?,
    @SerializedName("baseList") val baseList: List<FinlifeBase>?,
    @SerializedName("optionList") val optionList: List<FinlifeOption>?
)

data class FinlifeBase(
    @SerializedName("fin_co_no") val finCoNo: String?,
    @SerializedName("kor_co_nm") val bank: String?,
    @SerializedName("fin_prdt_cd") val code: String?,
    @SerializedName("fin_prdt_nm") val name: String?,
    @SerializedName("join_way") val joinWay: String?,
    @SerializedName("spcl_cnd") val specialCondition: String?,
    @SerializedName("join_member") val joinMember: String?,
    @SerializedName("join_deny") val joinDeny: String?,
    @SerializedName("etc_note") val etcNote: String?,
    @SerializedName("max_limit") val maxLimit: Long?
)

data class FinlifeOption(
    @SerializedName("fin_prdt_cd") val code: String?,
    @SerializedName("rsrv_type_nm") val rsrvTypeName: String?,
    @SerializedName("save_trm") val saveTerm: String?,
    @SerializedName("intr_rate") val rate: Double?,
    @SerializedName("intr_rate2") val rate2: Double?
)

data class CompanyResponse(val result: CompanyResult?)

data class CompanyResult(
    @SerializedName("err_cd") val errCd: String?,
    @SerializedName("err_msg") val errMsg: String?,
    @SerializedName("max_page_no") val maxPageNo: Int?,
    @SerializedName("baseList") val baseList: List<CompanyBase>?
)

data class CompanyBase(
    @SerializedName("fin_co_no") val finCoNo: String?,
    @SerializedName("kor_co_nm") val name: String?,
    @SerializedName("homp_url") val homepage: String?,
    @SerializedName("cal_tel") val tel: String?
)

class FinlifeException(message: String) : Exception(message)

/** API 응답(기본정보 + 금리옵션)을 앱에서 쓰는 MarketProduct 목록으로 합친다 */
fun FinlifeResult.toMarketProducts(kind: String, homepages: Map<String, String>): List<MarketProduct> {
    val bases = (baseList ?: emptyList()).associateBy { it.code }
    return (optionList ?: emptyList()).mapNotNull { o ->
        val b = bases[o.code] ?: return@mapNotNull null
        val term = o.saveTerm?.toIntOrNull() ?: return@mapNotNull null
        val base = o.rate ?: o.rate2 ?: return@mapNotNull null
        val name = (b.name ?: "").replace("\n", " ").trim()
        val joinMember = b.joinMember ?: ""
        MarketProduct(
            code = o.code ?: "",
            bank = b.bank ?: "",
            name = name,
            termMonths = term,
            baseRate = base,
            maxRate = maxOf(o.rate2 ?: base, base),
            rsrvType = o.rsrvTypeName ?: "",
            specialCondition = b.specialCondition ?: "",
            joinMember = joinMember,
            joinWay = b.joinWay ?: "",
            maxLimit = b.maxLimit,
            kind = kind,
            finCoNo = b.finCoNo ?: "",
            homepage = homepages[b.finCoNo] ?: "",
            etcNote = b.etcNote ?: "",
            monthlyCap = if (kind == ProductKind.SAVING) ProductRules.monthlyCap(b.maxLimit, b.etcNote ?: "") else null,
            restricted = ProductRules.isRestricted(name, joinMember, b.joinDeny ?: "")
        )
    }
}
