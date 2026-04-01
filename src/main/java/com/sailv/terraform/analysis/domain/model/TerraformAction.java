package com.sailv.terraform.analysis.domain.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板中实际发现的一条 Terraform 动作。
 *
 * <p>这里的 providerName 表示 Terraform 类型本身，例如：
 * <ul>
 *     <li>`huaweicloud_compute_instance`</li>
 *     <li>`huaweicloud_vpc_eip`</li>
 *     <li>`huaweicloud_images_image`</li>
 * </ul>
 *
 * <p>解析阶段只负责把动作和原始表达式收集出来；
 * 真正的数量、规格和盘大小求值在 `TerraformTemplate` 内部按 module 目录汇总 locals 后再做。
 * `requestedAmount` 表示资源最终解析后的数量，语义上等同于 Terraform `count` 的求值结果；
 * 如果 `count` 无法求值，则按约定退化成 `1`。
 */
@Getter
@Setter
@Accessors(chain = true)
public class TerraformAction {

    private String providerName;
    private String blockName;
    private ProviderType providerType;
    private int requestedAmount = 1;
    private String requestedAmountExpression;
    private Integer explicitCpuCount;
    private Integer explicitMemoryGiB;
    private String flavorId;
    private String flavorIdExpression;
    private Integer systemDiskSize;
    private String systemDiskSizeExpression;
    private List<Integer> dataDiskSizes = new ArrayList<>();
    private Integer volumeSize;
    private String volumeSizeExpression;

    public TerraformAction() {
    }

    public TerraformAction(String providerName, String blockName, ProviderType providerType, int requestedAmount) {
        this.providerName = providerName;
        this.blockName = blockName;
        this.providerType = providerType;
        this.requestedAmount = requestedAmount;
    }
}
