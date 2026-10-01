package com.sahahub.identity.domain;

/** İşletme içindeki personel rolleri. Yetkilerin karşılığı {@link RolePermissions} içindedir. */
public enum StaffRole {

	/** İşletmenin tüm şubelerinde yetkili. */
	OWNER("İşletme sahibi"),
	/** Yalnızca atandığı şubede yönetim yetkisi. */
	BRANCH_MANAGER("Şube yöneticisi"),
	/** Yalnızca atandığı şubede rezervasyon ve tahsilat işlemleri. */
	RECEPTION("Resepsiyon / kasa");

	private final String label;

	StaffRole(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
