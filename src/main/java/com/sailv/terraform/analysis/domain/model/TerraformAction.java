package com.sailv.terraform.analysis.domain.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

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
 * 真正的数量、规格和盘大小求值在 service 层按 module 目录汇总 locals 后再做。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Accessors(chain = true)
@EqualsAndHashCode(of = {
    "providerName",
    "blockName",
    "providerType",
    "requestedAmount",
    "requestedAmountExpression",
    "flavorId",
    "flavorIdExpression",
    "systemDiskSize",
    "systemDiskSizeExpression",
    "volumeSize",
    "volumeSizeExpression"
})
public class TerraformAction {

    private String providerName;
    private String blockName;
    private ProviderType providerType;
    private int requestedAmount = 1;
    private String requestedAmountExpression;
    private String flavorId;
    private String flavorIdExpression;
    private Integer systemDiskSize;
    private String systemDiskSizeExpression;
    private Integer volumeSize;
    private String volumeSizeExpression;

    public TerraformAction(
        String providerName,
        String blockName,
        ProviderType providerType,
        int requestedAmount
    ) {
        this.providerName = providerName;
        this.blockName = blockName;
        this.providerType = providerType;
        this.requestedAmount = requestedAmount;
    }

    public enum ProviderType {
        RESOURCE,
        DATA_SOURCE
    }
}
